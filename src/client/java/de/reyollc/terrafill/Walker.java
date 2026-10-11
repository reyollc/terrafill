package de.reyollc.terrafill;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;


import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/**
 * Laufen ueber die Oberflaeche: Breitensuche ueber begehbare Felder, dann
 * Schritt fuer Schritt von Feldmitte zu Feldmitte.
 *
 * Begehbar ist ein Feld, wenn darunter etwas Festes liegt und Fuss- und
 * Kopfhoehe frei sind. Erlaubt sind ebene Schritte, eine Stufe hoch (Sprung,
 * braucht Platz ueber dem Kopf) und bis zu drei Bloecke runter (kein
 * Fallschaden). Fluessigkeiten gelten nie als frei.
 *
 * Die Suche kennt keine feste Zielzelle, sondern bekommt eine Bewertung
 * (coverage): wie viele Zellen sind von diesem Standort aus baubar? Genommen
 * wird der Standort mit der besten Bilanz aus Abdeckung und Weglaenge.
 *
 * Fortbewegung: auf geraden, ebenen Abschnitten ueber die echte Eingabe
 * (Vorwaertstaste, Sprint), vor Kurven, Stufen und dem Ziel ueber
 * setDeltaMovement - genau wie DeepMine, nur mit Sprint dazwischen.
 */
final class Walker {

    record Node(int x, int y, int z) {
        long key() { return BlockPos.asLong(x, y, z); }
    }

    enum Status { WALKING, ARRIVED, STUCK }

    /** Bloecke pro Tick, knapp unter Sprinttempo (0.28). */
    private static final double SPEED = 0.26;
    /** Senkrechtes Tempo im Wasser; das Wasser bremst davon jeden Tick ein Fuenftel weg. */
    static final double SWIM_UP = 0.3;
    static final double DIVE = -0.25;
    /** So viele Ticks ohne Annaeherung an den naechsten Wegpunkt gelten als Steckenbleiben. */
    private static final int STALL_TICKS = 40;
    /** Knotenbudgets: in der Kachel reichen wenige, fuer Depot und Kachelwechsel braucht es viele. */
    static final int NODES_NEAR = 20_000;
    static final int NODES_FAR = 150_000;
    /** Nach dem ersten brauchbaren Standort wird nur noch so viel weiter gesucht. */
    private static final int EXTRA_DEPTH = 10;
    private static final int MAX_DROP = 3;
    private static final int MAX_DROP_WATER = 8;

    private List<Node> path = List.of();
    private int index;
    private double lastDist = Double.MAX_VALUE;
    private int stallTicks;

    boolean isActive() { return index < path.size(); }

    Node target() { return path.isEmpty() ? null : path.get(path.size() - 1); }
    /**
     * Sucht von start aus den besten Standort und merkt sich den Weg dorthin.
     * coverage liefert pro Feld, wie viele Zellen von dort aus baubar sind
     * (0 = kein Kandidat); avoid sind Felder, die sich schon als nutzlos
     * erwiesen haben. Liefert false, wenn es keinen brauchbaren Standort gibt.
     */
    boolean plan(ClientLevel level, Node start, Region bounds, ToIntFunction<Node> coverage, Set<Long> avoid) {
        return plan(level, start, bounds, coverage, avoid, 2, EXTRA_DEPTH, NODES_NEAR);
    }

    /**
     * Wie oben, mit eigener Gewichtung: depthPenalty Punkte Abzug pro Schritt
     * Weg, extraDepth Schritte Suche ueber den ersten Kandidaten hinaus, und
     * maxNodes als Budget - fuer die Standortsuche in der Kachel reicht ein
     * kleines, fuer den Weg zum Depot braucht es ein grosses. Ohne Abzug und
     * mit langer Suche wird schlicht der beste Standort genommen, egal wie weit.
     */
    boolean plan(ClientLevel level, Node start, Region bounds, ToIntFunction<Node> coverage, Set<Long> avoid,
                 int depthPenalty, int extraDepth, int maxNodes) {
        // Alles primitiv: eine Suche fasst tausende Knoten an, Boxing und
        // Objekt-Hashes machten daraus den spuerbaren Ruckler.
        Long2ObjectOpenHashMap<Node> parent = new Long2ObjectOpenHashMap<>();
        Long2IntOpenHashMap depth = new Long2IntOpenHashMap();
        LongOpenHashSet seen = new LongOpenHashSet();
        Long2ByteOpenHashMap cells = new Long2ByteOpenHashMap();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start.key());
        depth.put(start.key(), 0);

        Node best = null;
        int bestScore = Integer.MIN_VALUE;
        int firstDepth = -1;

        while (!queue.isEmpty()) {
            Node n = queue.poll();
            int d = depth.get(n.key());
            if (firstDepth >= 0 && d > firstDepth + extraDepth) break;

            if (!avoid.contains(n.key())) {
                int cov = coverage.applyAsInt(n);
                if (cov > 0) {
                    if (firstDepth < 0) firstDepth = d;
                    int score = cov - depthPenalty * d;
                    if (score > bestScore) {
                        bestScore = score;
                        best = n;
                    }
                }
            }
            if (seen.size() >= maxNodes) continue; // nichts mehr aufnehmen, nur noch leeren

            expand(level, cells, n, bounds, m -> {
                if (seen.add(m.key())) {
                    parent.put(m.key(), n);
                    depth.put(m.key(), d + 1);
                    queue.add(m);
                }
            });
        }

        if (best == null) {
            path = List.of();
            index = 0;
            return false;
        }
        List<Node> out = new ArrayList<>();
        for (Node c = best; c != null; c = parent.get(c.key())) out.add(c);
        Collections.reverse(out);
        path = out;
        index = 0;
        reset();
        return true;
    }
    /** Nur zur Feldmitte gehen, z.B. um sich fuer das Setzen der Nachbarn zu zentrieren. */
    void planTo(Node target) {
        path = List.of(target);
        index = 0;
        reset();
    }

    void clear() {
        releaseKeys(Minecraft.getInstance().player);
        path = List.of();
        index = 0;
    }

    /**
     * Alle Felder, die der Weg braucht: Fuss und Kopf jedes Wegpunkts, beim
     * Sprung der Platz ueber dem Kopf, beim Runterfallen die durchfallenen
     * Felder. Die duerfen waehrend des Laufens nicht verbaut werden.
     */
    void collectPathCells(Set<Long> out) {
        Node prev = null;
        for (Node n : path) {
            out.add(BlockPos.asLong(n.x, n.y, n.z));
            out.add(BlockPos.asLong(n.x, n.y + 1, n.z));
            if (prev != null) {
                if (n.y > prev.y) {
                    out.add(BlockPos.asLong(prev.x, prev.y + 2, prev.z));
                    out.add(BlockPos.asLong(n.x, n.y + 2, n.z));
                }
                for (int y = n.y + 1; y <= prev.y + 1; y++) out.add(BlockPos.asLong(n.x, y, n.z));
            }
            prev = n;
        }
    }

    /** Zellenarten fuer den Cache einer Suche: jede Position wird von bis zu vier Nachbarn gefragt. */
    private static final byte CELL_SOLID = 1;
    private static final byte CELL_FREE = 2;
    private static final byte CELL_BLOCKED = 3;   // keine Kollision, aber Lava oder (im Deckel-Modus) Wasser
    private static final byte CELL_WATER = 4;     // frei und Wasser - nur im Zuschuett-Modus

    private static void expand(ClientLevel level, Long2ByteOpenHashMap cells, Node n, Region bounds, Consumer<Node> out) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int nx = n.x + d.getStepX();
            int nz = n.z + d.getStepZ();
            if (!bounds.containsXZ(nx, nz)) continue;

            // Stufe hoch: Sprung, braucht Platz ueber dem eigenen Kopf.
            if (n.y + 1 <= bounds.maxY() && walkableC(level, cells, nx, n.y + 1, nz) && freeC(level, cells, n.x, n.y + 2, n.z)) {
                out.accept(new Node(nx, n.y + 1, nz));
            }
            if (walkableC(level, cells, nx, n.y, nz)) {
                out.accept(new Node(nx, n.y, nz));
                continue;
            }
            // Runter: alles, wo man durchfaellt, muss frei sein, und unten muss
            // Boden sein. Drei Bloecke ohne Schaden; landet man im Wasser, auch tiefer.
            if (!freeC(level, cells, nx, n.y, nz) || !freeC(level, cells, nx, n.y + 1, nz)) continue;
            for (int k = 1; k <= MAX_DROP_WATER; k++) {
                int y = n.y - k;
                if (y < bounds.minY()) break;
                if (walkableC(level, cells, nx, y, nz)) {
                    if (k <= MAX_DROP || cell(level, cells, nx, y, nz) == CELL_WATER) out.accept(new Node(nx, y, nz));
                    break;
                }
                if (!freeC(level, cells, nx, y, nz)) break;
            }
        }
    }

    private static boolean walkableC(ClientLevel level, Long2ByteOpenHashMap cells, int x, int y, int z) {
        return cell(level, cells, x, y - 1, z) == CELL_SOLID && freeC(level, cells, x, y, z) && freeC(level, cells, x, y + 1, z);
    }

    private static boolean freeC(ClientLevel level, Long2ByteOpenHashMap cells, int x, int y, int z) {
        byte c = cell(level, cells, x, y, z);
        return c == CELL_FREE || c == CELL_WATER;
    }

    private static final ThreadLocal<BlockPos.MutableBlockPos> SCRATCH = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    /** Zellenart aus dem Cache, sonst einmal aus der Welt lesen. */
    private static byte cell(ClientLevel level, Long2ByteOpenHashMap cells, int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        byte c = cells.get(key);
        if (c != 0) return c;
        BlockPos.MutableBlockPos m = SCRATCH.get().set(x, y, z);
        BlockState st = level.getBlockState(m);
        if (!st.getCollisionShape(level, m).isEmpty()) {
            c = CELL_SOLID;
        } else {
            FluidState fluid = st.getFluidState();
            if (fluid.isEmpty()) c = CELL_FREE;
            else if (waterWalkable && fluid.is(FluidTags.WATER)) c = CELL_WATER;
            else c = CELL_BLOCKED;
        }
        cells.put(key, c);
        return c;
    }

    static boolean walkable(ClientLevel level, int x, int y, int z) {
        return solid(level, x, y - 1, z) && free(level, x, y, z) && free(level, x, y + 1, z);
    }

    /** Traegt den Spieler: irgendeine Kollisionsform, egal welche. */
    static boolean solid(ClientLevel level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** Darf durch Wasser gelaufen werden? Im Deckel-Modus nicht - dann fuehrt der Weg ueber den Deckel. */
    private static boolean waterWalkable;

    static void setWaterWalkable(boolean on) { waterWalkable = on; }

    /**
     * Frei zum Durchlaufen: keine Kollision, und hoechstens Wasser - und das
     * auch nur, wenn Wasser komplett zugeschuettet wird (dann wird gewatet
     * und getaucht). Lava bleibt immer tabu. Ums Luftholen kuemmert sich
     * FillTask.
     */
    static boolean free(ClientLevel level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState st = level.getBlockState(pos);
        if (!st.getCollisionShape(level, pos).isEmpty()) return false;
        FluidState fluid = st.getFluidState();
        return fluid.isEmpty() || (waterWalkable && fluid.is(FluidTags.WATER));
    }

    /** Kann man hier Luft holen? Kopfhoehe ohne Wasser. */
    static boolean headInAir(ClientLevel level, int x, int y, int z) {
        return level.getBlockState(new BlockPos(x, y + 1, z)).getFluidState().isEmpty();
    }

    /** Steht auf diesem Feld Wasser? Dann tut ein Sturz dorthin nicht weh. */
    static boolean inWater(ClientLevel level, int x, int y, int z) {
        return level.getBlockState(new BlockPos(x, y, z)).getFluidState().is(FluidTags.WATER);
    }

    // ------------------------------------------------------------------

    /**
     * Einen Tick laufen. ARRIVED, sobald der letzte Wegpunkt erreicht ist und
     * der Spieler am Boden steht.
     *
     * Zwei Arten der Fortbewegung: Auf geraden, ebenen Abschnitten wird wie
     * ein Spieler gelaufen - Vorwaertstaste halten, Sprint an, das Spiel macht
     * Beschleunigung, Reibung und Animation. Vor Kurven, Stufen, Absaetzen und
     * vor dem Ziel wird auf die genaue Steuerung ueber setDeltaMovement
     * umgeschaltet: mit Schwung kaeme man weder sauber um die Ecke noch genau
     * in der Feldmitte an, und beim Runterfallen traegt der Sprint einen Block
     * ueber das Landefeld hinaus. Im Wasser gilt immer die genaue Steuerung.
     */
    Status tick(LocalPlayer p) {
        Node n;
        double dx;
        double dz;
        double dist;
        boolean passThrough;
        while (true) {
            if (index >= path.size()) {
                halt(p);
                return Status.ARRIVED;
            }
            n = path.get(index);
            dx = n.x + 0.5 - p.getX();
            dz = n.z + 0.5 - p.getZ();
            dist = Math.sqrt(dx * dx + dz * dz);
            boolean last = index == path.size() - 1;
            passThrough = !last && straightThrough(p, n, path.get(index + 1));
            // Hoehe mit Toleranz: auf einer Platte oder einem Teppich steht man
            // etwas unter der Feldhoehe, eine ganze Stufe daneben zaehlt nicht.
            // Wegpunkte, durch die es geradeaus weitergeht, gelten schon beim
            // Vorbeilaufen als erreicht.
            double tolerance = passThrough ? 0.35 : 0.15;
            boolean reached = dist < tolerance && Math.abs(p.getY() - n.y) < 0.95 && (!last || p.onGround());
            if (!reached) break;
            index++;
            reset();
        }

        if (dist < lastDist - 0.005) {
            lastDist = dist;
            stallTicks = 0;
        } else if (++stallTicks > STALL_TICKS) {
            halt(p);
            return Status.STUCK;
        }

        lookAt(p, n.x + 0.5, p.getEyeY(), n.z + 0.5);

        if (!p.isInWater()) {
            boolean levelStep = Math.abs(n.y - p.getY()) <= 0.5;
            if (passThrough && levelStep) {
                // Gerade Strecke: laufen wie ein Spieler, mit Sprint.
                run(p);
                return Status.WALKING;
            }
            releaseKeys(p);
            // Mehr als eine halbe Stufe hoch schafft das Laufen nicht von allein.
            if (n.y > p.getY() + 0.5 && p.onGround() && dist < 1.4) p.jumpFromGround();
            steer(p, dx, dz, dist, p.getDeltaMovement().y);
            return Status.WALKING;
        }

        // Im Wasser wird die Hoehe direkt gesteuert: hochschwimmen statt
        // springen, tauchen statt minutenlang absinken.
        //
        // Stufe hoch: schwimmen, bis die Fuesse ueber der Stufenkante
        // sind; schwebend gibt es keine Stufenhilfe, man muss wirklich
        // drueber. Getaucht wird nur, wenn man deutlich UEBER dem Ziel ist
        // oder am letzten Wegpunkt genau darueber schwebt. Wer neben der
        // Stufe oder unterhalb des Ziels taucht, pendelt unter der Kante
        // hin und her und kommt nie hinauf - genau das war der Fehler.
        releaseKeys(p);
        double dy = n.y - p.getY();
        boolean last = index == path.size() - 1;
        double vy;
        if (dy > 0.05) vy = SWIM_UP;
        else if (dy < -0.6 || (last && !p.onGround() && dist < 0.4)) vy = DIVE;
        else vy = p.getDeltaMovement().y;
        steer(p, dx, dz, dist, vy);
        return Status.WALKING;
    }

    /**
     * Geht es am Wegpunkt n geradeaus weiter? Dann muss dort nicht angehalten
     * werden. Gerade heisst: dieselbe Richtung wie die Anfahrt auf n, und
     * keine Hoehenaenderung danach.
     */
    private static boolean straightThrough(LocalPlayer p, Node n, Node after) {
        if (after.y != n.y) return false;
        double ax = n.x + 0.5 - p.getX();
        double az = n.z + 0.5 - p.getZ();
        double bx = after.x - n.x;
        double bz = after.z - n.z;
        double la = Math.sqrt(ax * ax + az * az);
        if (la < 1.0e-6) return true;
        // Winkel unter etwa 25 Grad zaehlt als geradeaus.
        return (ax * bx + az * bz) / la > 0.9;
    }

    /** Vorwaertstaste halten und sprinten - der Blick zeigt schon zum Wegpunkt. */
    private static void run(LocalPlayer p) {
        Minecraft.getInstance().options.keyUp.setDown(true);
        if (!p.isSprinting()) p.setSprinting(true);
    }

    /** Genaue Steuerung: Tempo proportional zur Restdistanz, damit die Feldmitte getroffen wird. */
    private static void steer(LocalPlayer p, double dx, double dz, double dist, double vy) {
        if (dist > 1.0e-6) {
            double v = Math.min(SPEED, dist);
            p.setDeltaMovement(dx / dist * v, vy, dz / dist * v);
        } else {
            p.setDeltaMovement(0.0, vy, 0.0);
        }
    }

    /** Tasten loslassen und Sprint beenden - vor jeder genauen Steuerung und bei jedem Halt. */
    static void releaseKeys(LocalPlayer p) {
        Minecraft.getInstance().options.keyUp.setDown(false);
        if (p != null && p.isSprinting()) p.setSprinting(false);
    }

    void halt(LocalPlayer p) {
        releaseKeys(p);
        p.setDeltaMovement(0.0, p.getDeltaMovement().y, 0.0);
    }

    private void reset() {
        lastDist = Double.MAX_VALUE;
        stallTicks = 0;
    }

    private static void lookAt(LocalPlayer p, double x, double y, double z) {
        Vec3 eye = p.getEyePosition();
        double dx = x - eye.x;
        double dy = y - eye.y;
        double dz = z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        p.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        p.setXRot((float) (-Math.toDegrees(Math.atan2(dy, flat))));
    }
}
