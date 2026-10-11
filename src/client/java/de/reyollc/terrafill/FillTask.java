package de.reyollc.terrafill;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Fuellt einen Bereich Ebene fuer Ebene von unten nach oben mit dem Baublock.
 *
 * Was gefuellt wird, entscheidet der Blick von oben beim Start: jede Spalte
 * des Grundrisses von der Oberkante abwaerts durch alles Fuellbare (Luft,
 * Wasser, Gras, Blumen ...) bis zum ersten festen Block. So werden Loecher,
 * Taeler und Fluesse erfasst, aber nichts, was unter dem Gelaende liegt -
 * keine Hoehlen, keine Ueberhaenge, kein Gang unter einem Loch im Flussbett.
 * Zellen, an die er von keinem Standort aus herankommt, werden uebersprungen
 * und am Ende gezaehlt, statt den Auftrag anzuhalten.
 *
 * Gesetzt wird ueber gameMode.useItemOn, also ueber denselben Weg wie ein
 * Rechtsklick - nur mehrmals pro Tick. Der Server prueft dabei nur die
 * Reichweite zum angeklickten Block, keine Sichtlinie. Als Klickflaeche dient
 * bevorzugt der Block darunter; beim Auffuellen von unten ist das fast immer
 * der Halt.
 *
 * Zwischen den Standorten laeuft der Walker ueber die Oberflaeche, waehrend
 * des Laufens wird weitergesetzt, nur die Felder des Weges bleiben frei.
 * Geht der Baublock aus, uebernimmt der Restocker.
 */
public final class FillTask {

    private enum State { FILL, WALK, TRAVEL, RESTOCK, DEPOT, BREATHE, ESCAPE, FIGHT, HEAL, EAT, DONE }
    private enum Placement { PLACED, FAILED, NO_SUPPORT }

    private static final Logger LOG = LoggerFactory.getLogger("terrafill");

    /** Mehr Zellen flutet der Start nicht - der Speicher fuer die Ebenenlisten waere sonst unhandlich. */
    public static final long MAX_CELLS = 2_000_000L;
    /** So nah muss ein Tier sein, damit es einen Schubs mit dem Block bekommt. */
    private static final double SHOVE_RANGE = 4.0;

    /** Reihenfolge der Klickflaechen: unten zuerst, dann die Seiten, zuletzt von oben. */
    private static final Direction[] SUPPORT_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP };
    /** Haerte, bis zu der ein Block ohne Kollision einfach weggeschlagen wird (Blumen, Setzlinge, Seegras). */
    private static final float SOFT_HARDNESS = 0.2f;
    /** Nach einem gescheiterten Setzversuch (Mob im Weg) so lange nicht wieder probieren. */
    private static final int BACKOFF_TICKS = 20;
    /** So lange auf blockierte Zellen in Reichweite warten, bevor ein neuer Standort gesucht wird. */
    private static final int IDLE_LIMIT = 10;
    private static final int MAX_REPLANS = 3;
    /** Nach dem letzten Setzen so lange auf die Serverantwort warten, bevor eine Ebene als fertig gilt. */
    private static final int SETTLE_TICKS = 15;
    /** So lange nach dem Setzen gilt ein Block als "vom Server noch nicht bestaetigt". */
    private static final int CONFIRM_TICKS = 40;
    /** Ohne Ruecknahme so lange warten, dann darf das Tempo wieder um eins steigen. */
    private static final int RECOVER_TICKS = 200;
    /** Unter Wasser: bei so viel Restluft (Ticks, voll sind 300) wird aufgetaucht. */
    private static final int AIR_LOW = 100;

    // Kampf, Heilen, Essen - Werte und Logik wie in DeepMine.
    /** Angriffsreichweite im Ueberlebensmodus. Weiter weg wird nicht zugeschlagen. */
    private static final double ATTACK_RANGE = 3.0;
    /** Ab dieser Entfernung wird ein Gegner angegangen, nicht erst nach dem ersten Treffer. */
    private static final double ENGAGE_RANGE = 4.0;
    /** Umkreis, in dem nach einem Angreifer gesucht wird. */
    private static final double TARGET_SEARCH = 10.0;
    /** Ohne erreichbares Ziel so lange warten, dann weiterbauen. */
    private static final int FIGHT_GIVEUP_TICKS = 40;
    /** Harte Obergrenze fuer einen Kampf, damit nichts endlos haengt. */
    private static final int FIGHT_MAX_TICKS = 400;
    private static final double APPROACH_SPEED = 0.2;
    /** Unter diesem Leben wird geheilt (mit Schwert) bzw. gestoppt (ohne). */
    private static final float HEALTH_FLOOR = 12.0f;
    /** Heilen endet bei diesem Leben, bewusst ueber der Schwelle (Hysterese). */
    private static final float HEAL_UNTIL = 16.0f;
    /** Laenger als eine Minute ohne Erholung: dann stimmt etwas nicht. */
    private static final int HEAL_MAX_TICKS = 1200;
    /** Ab diesem Hungerwert (von 20) wird gegessen. Unter 6 kann man nicht sprinten. */
    private static final int EAT_AT = 12;
    /** Beim Heilen frueher essen: Regeneration laeuft erst ab Hunger 18. */
    private static final int EAT_AT_HEAL = 17;
    /** So lange darf das Essen brauchen, bis es anfaengt (danach ist der Slot wohl nicht essbar). */
    private static final int EAT_MAX_TICKS = 60;
    /** Nach einem nicht gestarteten Essen so lange nicht wieder versuchen, damit nicht jeder Tick EAT triggert. */
    private static final int EAT_COOLDOWN = 200;
    /** So viele Nachschub-Pannen hintereinander ohne Fortschritt, dann wird wirklich gestoppt. */
    private static final int RESTOCK_SETBACK_LIMIT = 3;
    /** So weit darf der Walker ueber den Bereich hinaus laufen. */
    private static final int WALK_MARGIN = 8;
    private static final int WALK_VERTICAL = 6;
    /** Umkreis, in dem ein Standort nach baubaren Zellen abgesucht wird. */
    private static final int COVER_RADIUS = 5;
    /**
     * Lochmodus: so weit (waagerecht) darf die Senke um den Spieler reichen und
     * dabei noch von festen Bloecken eingeschlossen sein. Was weiter reicht, ist
     * kein Loch mehr, sondern offenes Gelaende.
     */
    private static final int HOLE_RADIUS = 12;
    /** Hochmauern: so viele Bloecke hintereinander, ohne dass sonst etwas voranging, dann ist es sinnlos. */
    private static final int PILLAR_MAX = 4;
    /** So lange darf ein Sprung fuers Hochmauern dauern, bis er als gescheitert gilt. */
    private static final int PILLAR_TICKS = 20;

    private final Minecraft mc = Minecraft.getInstance();
    /** Der ganze Grundriss mit Hoehenbereich; gearbeitet wird in Kacheln davon. */
    private final Region footprint;
    private final List<Region> tiles = new ArrayList<>();
    private int tileIndex = -1;
    /** Die aktuelle Kachel - alles Weitere (Abtastung, Ebenen, Laufgrenzen) bezieht sich darauf. */
    private Region region;
    private boolean tileScanned;
    private int tileWait;
    private long totalPlacedBefore;   // gesetzt vor der aktuellen Kachel, fuer die Fortschrittsanzeige
    private long totalSkipped;
    private final String dimension;
    private final Item buildItem;
    private final Block buildBlock;
    private final Walker walker = new Walker();
    private final Restocker restocker;
    private Depot depot;
    private Region depotArea;

    private int slot;
    private int speed = 8;
    private double reach = 4.5;
    private boolean waterLid = true;

    /** Ergebnis der Flutung: Zellen je Ebene, aufsteigend nach Y. */
    private final TreeMap<Integer, long[]> layers = new TreeMap<>();
    private long total;
    private long placed;
    private int layerY;
    private final List<BlockPos> remaining = new ArrayList<>();
    private final Map<Long, Integer> blockedUntil = new HashMap<>();
    /** Frisch gesetzte Bloecke mit Tick: taucht einer davon wieder als Luft auf, hat der Server ihn zurueckgenommen. */
    private final Map<Long, Integer> pending = new HashMap<>();
    private int reverted;          // in dieser Kachel vom Server zurueckgenommen
    private int revertBackoff;     // so viel weniger Bloecke pro Tick als eingestellt
    private int lastRevertTick = Integer.MIN_VALUE / 2;
    /** Standorte, von denen aus zuletzt nichts ging - bis wieder etwas gesetzt wurde. */
    private final Set<Long> badSpots = new HashSet<>();
    /** Felder des aktuellen Weges, die nicht verbaut werden duerfen. */
    private final Set<Long> pathCells = new HashSet<>();
    /** Zellen, an die er von keinem Standort aus herankam. Werden uebersprungen, nicht endlos versucht. */
    private final LongOpenHashSet skipped = new LongOpenHashSet();

    private State state = State.FILL;
    private boolean running;
    private int tick;
    private int lastPlaceTick = Integer.MIN_VALUE / 2;
    private int idleTicks;
    private int replans;
    private boolean blockedInReach;
    private boolean skippedByBody;

    /**
     * Lochmodus: nur wenn er festhaengt. Dann zaehlt nicht mehr die Ebene der
     * ganzen Kachel, sondern nur das Loch, in dem er steht - die von festen
     * Bloecken eingeschlossene Senke rund um seine Fuesse, Ebene fuer Ebene
     * von unten nach oben, bis er oben wieder auf offenem Gelaende steht.
     * Auf offenem Gelaende gibt es kein Loch, und dann auch keinen Lochmodus.
     */
    private boolean local;
    /** Spalten des Lochs (x/z als BlockPos-Schluessel mit y = 0) und seine Ebenen, von unten. */
    private final LongOpenHashSet holeColumns = new LongOpenHashSet();
    private final TreeMap<Integer, long[]> holeLayers = new TreeMap<>();
    private int holeTop;
    private int mainLayerY;      // Ebene der Kachel, zu der es nach dem Loch zurueckgeht
    /** Hochmauern: das eigene Fussfeld im Sprung setzen, wenn sonst nichts mehr geht. */
    private BlockPos pillarTarget;
    private int pillarTicks;
    private int pillars;         // seit dem letzten normalen Fortschritt
    private boolean swimUp;      // BREATHE: senkrecht hoch statt zum Ufer laufen
    private int breatheTicks;
    private int lastSkipMessageTick = Integer.MIN_VALUE / 2;
    private int stuckEpisodes;
    private long placedAtRetry;
    /** Einmal pro Festhaengen: erst so nah wie moeglich an die offenen Zellen heranlaufen. */
    private boolean approached;

    // Hotbar-Slots, 0-basiert, -1 = aus. Ohne Schwert wird nicht gekaempft,
    // ohne Essen bleibt nur der Hunger-Abbruch.
    private int swordSlot = -1;
    private int foodSlot = -1;
    private State resumeState = State.FILL;   // Bau-Zustand, zu dem Heilen zurueckkehrt
    private State fightReturn = State.FILL;   // wohin nach dem Kampf (auch HEAL oder RESTOCK)
    private State eatReturn = State.FILL;     // wohin nach dem Essen (HEAL oder Bau-Zustand)
    private float lastHealth = -1.0f;
    private int fightTicks;
    private int noTargetTicks;
    private int fightCooldown;                 // nach einem Timeout-Kampf kurz nicht wieder anfangen
    private int healTicks;
    private float healPrevHp = -1.0f;   // Leben im vorigen Heil-Tick, um "sinkt trotz Wand" zu erkennen
    private int eatTicks;
    private boolean wasEating;
    private int eatCooldown;            // nach fehlgeschlagenem Essen kurze Sperre, statt hart zu stoppen
    private int restockSetbacks;        // Nachschub-Pannen hintereinander ohne Fortschritt
    private boolean restockAfterWalk;   // nach dem kurzen Umsetzen wieder in den Nachschub

    public FillTask(Region footprint, String dimension, Block block, Item item) {
        this.footprint = footprint;
        this.region = footprint;
        this.dimension = dimension;
        this.buildBlock = block;
        this.buildItem = item;
        this.restocker = new Restocker(mc, item);
    }

    public void setSlot(int slot) {
        this.slot = slot;
        restocker.setSlot(slot);
    }

    public void setSpeed(int speed) { this.speed = Math.max(1, speed); }

    /** Reichweite fuers Setzen; nie weiter, als der Server annimmt (Attribut + 1, mit etwas Luft). */
    public void setReach(double reach) { this.reach = reach; }

    /**
     * Deckel-Modus: Wasser bekommt nur die oberste Zelle gesetzt, und der
     * Walker meidet Wasser ganz - der Deckel wird vom Ufer aus gebaut und
     * dann als Weg benutzt. Wirkt auf die Abtastung erst beim naechsten Start.
     */
    public void setWaterLid(boolean lid) {
        this.waterLid = lid;
        Walker.setWaterWalkable(!lid);
    }

    /** Lagerbereich fuer den Nachschub-Gang; null = kein Depot, dann ist bei leeren Shulkern Schluss. */
    public void setDepot(Region area) {
        this.depotArea = area;
    }
    public void setSwordSlot(int slot) { this.swordSlot = slot; }
    public void setFoodSlot(int slot) { this.foodSlot = slot; }

    public boolean isRunning() { return running; }
    public boolean isDone() { return state == State.DONE; }
    public Region footprint() { return footprint; }
    public int tileCount() { return tiles.size(); }
    public long total() { return total; }
    public int firstLayer() { return layers.isEmpty() ? 0 : layers.firstKey(); }
    public int lastLayer() { return layers.isEmpty() ? 0 : layers.lastKey(); }

    public MutableComponent blockName() {
        return Component.translatable(buildBlock.getDescriptionId());
    }

    /** Zustandsname in der Spielsprache. */
    public MutableComponent stateName() {
        if (local && (state == State.FILL || state == State.WALK)) return Chat.tr("terrafill.state.local");
        return Chat.tr("terrafill.state." + state.name().toLowerCase(Locale.ROOT));
    }

    /** "Kachel 3/12, Ebene 64, 120 Zellen offen, 900 gesetzt (Kachel 40 von 300), Zustand baut." */
    public MutableComponent statusLine() {
        return Chat.tr("terrafill.status.line", Chat.hl(Math.max(1, tileIndex + 1)), Chat.hl(tiles.size()),
                Chat.hl(layerY), Chat.hl(remaining.size()), Chat.hl(placed),
                Chat.hl(placed - totalPlacedBefore), Chat.hl(total), stateName());
    }

    // ------------------------------- Flutung -------------------------------

    /**
     * Sammelt, was von oben zu sehen ist: jede Spalte des Grundrisses von der
     * Oberkante abwaerts durch alles Fuellbare, bis der erste feste Block
     * kommt. Was darunter liegt - Hoehlen, Ueberhaenge, der Gang unter einem
     * Loch im Flussbett - bleibt unangetastet. Blaetter werden durchschaut,
     * aber nicht gefuellt: der Baum bleibt, das Gelaende steigt um ihn herum.
     * Liefert die Zahl der Zellen, 0 wenn nichts zu tun ist, -1 wenn es mehr
     * als MAX_CELLS wuerden.
     */
    public long scan(ClientLevel level) {
        layers.clear();
        skipped.clear();
        placedAtRetry = 0;
        total = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        Map<Integer, LongArrayList> byLayer = new HashMap<>();
        long count = 0;

        for (int x = region.minX(); x <= region.maxX(); x++) {
            for (int z = region.minZ(); z <= region.maxZ(); z++) {
                for (int y = region.maxY(); y >= region.minY(); y--) {
                    m.set(x, y, z);
                    if (!level.isLoaded(m)) break;
                    BlockState st = level.getBlockState(m);
                    if (fillable(st, level, m)) {
                        boolean fluid = !st.getFluidState().isEmpty();
                        byLayer.computeIfAbsent(y, i -> new LongArrayList()).add(m.asLong());
                        if (++count > MAX_CELLS) {
                            layers.clear();
                            LOG.warn("TerraFill: mehr als {} Zellen, Abbruch", MAX_CELLS);
                            return -1;
                        }
                        // Deckel-Modus: die oberste Wasserzelle wird gesetzt, darunter
                        // bleibt das Wasser - da spawnt nichts, und er muss nie hinein.
                        if (waterLid && fluid) break;
                    } else if (!st.is(BlockTags.LEAVES)) {
                        break;
                    }
                }
            }
        }
        for (Map.Entry<Integer, LongArrayList> e : byLayer.entrySet()) {
            layers.put(e.getKey(), e.getValue().toLongArray());
            total += e.getValue().size();
        }
        LOG.info("TerraFill: {} Zellen in {} Ebenen zu fuellen", total, layers.size());
        return total;
    }

    /** Luft, Wasser, Gras, Schnee: kann direkt ueberbaut werden. Blumen und Co. erst nach dem Wegschlagen. */
    private static boolean fillable(ClientLevel level, BlockPos pos) {
        return fillable(level.getBlockState(pos), level, pos);
    }

    private static boolean fillable(BlockState st, ClientLevel level, BlockPos pos) {
        return st.canBeReplaced() || soft(st, level, pos);
    }

    /**
     * Nicht ueberbaubar, aber ohne Widerstand wegzuschlagen: alles mit Haerte 0
     * (Blumen, Setzlinge, Seerosen, Seegurken) und die duennen Dinger bis
     * Haerte 0.2 ohne Kollision (Kelp, Ranken).
     */
    private static boolean soft(BlockState st, ClientLevel level, BlockPos pos) {
        if (st.isAir() || st.canBeReplaced()) return false;
        float hardness = st.getDestroySpeed(level, pos);
        if (hardness < 0.0f) return false;
        if (hardness == 0.0f) return true;
        return hardness <= SOFT_HARDNESS && st.getCollisionShape(level, pos).isEmpty();
    }

    // ---------------------------- Start / Stopp ----------------------------

    /** Kachelkante. 32 passt in jede Sichtweite und haelt die Wege je Ebene kurz. */
    private static final int TILE = 32;
    /** Beim Anlaufen einer ungeladenen Kachel: ab dieser Naehe zur Mitte wird nur noch gewartet. */
    private static final double TILE_NEAR = 6.0;
    private static final int TILE_LOAD_WAIT = 200;

    public void start(ClientLevel level, BlockPos from) {
        running = true;
        state = State.FILL;
        tick = 0;
        idleTicks = 0;
        replans = 0;
        placed = 0;
        totalPlacedBefore = 0;
        totalSkipped = 0;
        blockedUntil.clear();
        badSpots.clear();
        pathCells.clear();
        walker.clear();
        clearLocal();
        Walker.setWaterWalkable(!waterLid);
        approached = false;
        stuckEpisodes = 0;
        restockSetbacks = 0;
        restockAfterWalk = false;
        eatCooldown = 0;
        healPrevHp = -1.0f;
        lastHealth = -1.0f;
        fightCooldown = 0;
        buildTiles(from);
        tileIndex = 0;
        enterTile(level);
        Chat.info("terrafill.tiles", Chat.hl(tiles.size()), Chat.hl(TILE), Chat.hl(TILE));
    }

    /**
     * Den Grundriss in Kacheln schneiden, in Schlangenlinie ab der Ecke, die
     * dem Spieler am naechsten liegt: so ist jede naechste Kachel die
     * Nachbarin der vorigen, und der Weg dorthin fuehrt ueber fertiges Gelaende.
     */
    private void buildTiles(BlockPos from) {
        tiles.clear();
        int nx = (footprint.sizeX() + TILE - 1) / TILE;
        int nz = (footprint.sizeZ() + TILE - 1) / TILE;
        boolean fromMinX = Math.abs(from.getX() - footprint.minX()) <= Math.abs(from.getX() - footprint.maxX());
        boolean fromMinZ = Math.abs(from.getZ() - footprint.minZ()) <= Math.abs(from.getZ() - footprint.maxZ());
        for (int iz = 0; iz < nz; iz++) {
            int tz = fromMinZ ? iz : nz - 1 - iz;
            boolean forward = (iz % 2 == 0) == fromMinX;
            for (int ix = 0; ix < nx; ix++) {
                int tx = forward ? ix : nx - 1 - ix;
                int x1 = footprint.minX() + tx * TILE;
                int z1 = footprint.minZ() + tz * TILE;
                tiles.add(new Region(x1, footprint.minY(), z1,
                        Math.min(footprint.maxX(), x1 + TILE - 1), footprint.maxY(), Math.min(footprint.maxZ(), z1 + TILE - 1)));
            }
        }
    }

    /** Die Kachel tileIndex zur aktuellen machen; abgetastet wird sie erst, wenn sie geladen ist. */
    private void enterTile(ClientLevel level) {
        region = tiles.get(tileIndex);
        tileScanned = false;
        layers.clear();
        remaining.clear();
        totalSkipped += skipped.size();
        skipped.clear();
        placedAtRetry = placed;
        totalPlacedBefore = placed;
        total = 0;
        blockedUntil.clear();
        badSpots.clear();
        pathCells.clear();
        walker.clear();
        clearLocal();
        approached = false;
        stuckEpisodes = 0;
        replans = 0;
        idleTicks = 0;
        tileWait = 0;
        pending.clear();
        reverted = 0;
    }

    /** Naechste Kachel, oder fertig. */
    private void nextTile(ClientLevel level) {
        tileIndex++;
        if (tileIndex >= tiles.size()) {
            finish();
            return;
        }
        enterTile(level);
    }

    /** Sind alle Chunks der Kachel geladen? Die Hoehe spielt fuers Laden keine Rolle. */
    private static boolean tileLoaded(ClientLevel level, Region tile) {
        int y = Math.max(level.getMinY(), Math.min(level.getMaxY(), tile.minY()));
        for (int cx = tile.minX() >> 4; cx <= tile.maxX() >> 4; cx++) {
            for (int cz = tile.minZ() >> 4; cz <= tile.maxZ() >> 4; cz++) {
                if (!level.isLoaded(new BlockPos(cx << 4, y, cz << 4))) return false;
            }
        }
        return true;
    }

    /**
     * Die aktuelle Kachel abtasten, sobald sie geladen ist; vorher zur
     * Kachelmitte laufen. Liefert true, wenn es in der Kachel Arbeit gibt.
     */
    private boolean ensureTile(LocalPlayer p, ClientLevel level) {
        if (tileScanned) return true;
        if (!tileLoaded(level, region)) {
            travelToTile(p, level);
            return false;
        }
        long cells = scan(level);
        if (cells < 0) {
            stop(Chat.tr("terrafill.start.too_many", Chat.hl(MAX_CELLS)));
            return false;
        }
        tileScanned = true;
        if (cells == 0) {
            nextTile(level);
            return false;
        }
        Chat.info("terrafill.tile.start", Chat.hl(tileIndex + 1), Chat.hl(tiles.size()), Chat.hl(cells),
                Chat.hl(layers.firstKey()), Chat.hl(layers.lastKey()));
        layerY = layers.firstKey();
        if (!findWork(level, true)) {
            nextTile(level);
            return false;
        }
        return true;
    }

    /**
     * Zur Mitte der Kachel laufen, bis ihre Chunks da sind. In der Naehe der
     * Mitte wird nur noch gewartet - laden die Chunks auch dann nicht, ist die
     * Sichtweite zu klein.
     */
    private void travelToTile(LocalPlayer p, ClientLevel level) {
        BlockPos center = new BlockPos((region.minX() + region.maxX()) / 2, p.blockPosition().getY(),
                (region.minZ() + region.maxZ()) / 2);
        double dx = center.getX() + 0.5 - p.getX();
        double dz = center.getZ() + 0.5 - p.getZ();
        if (Math.sqrt(dx * dx + dz * dz) <= TILE_NEAR) {
            if (++tileWait > TILE_LOAD_WAIT) {
                stop(Chat.tr("terrafill.stop.tile_not_loaded", Chat.hl(tileIndex + 1), Chat.pos(center)));
            }
            return;
        }
        if (state == State.TRAVEL) return; // Walker laeuft schon
        BlockPos feet = p.blockPosition();
        Walker.Node start = new Walker.Node(feet.getX(), feet.getY(), feet.getZ());
        Region bounds = walkBounds(level, feet).including(center, 4);
        bounds = new Region(bounds.minX(), Math.max(level.getMinY(), bounds.minY()), bounds.minZ(),
                bounds.maxX(), Math.min(level.getMaxY(), bounds.maxY()), bounds.maxZ());
        Vec3 target = Vec3.atCenterOf(center);
        double now = target.distanceTo(Vec3.atCenterOf(feet));
        boolean ok = walker.plan(level, start, bounds, n -> {
            double d = Math.hypot(n.x() + 0.5 - target.x, n.z() + 0.5 - target.z);
            return d < now - 1.0 ? Math.max(1, (int) Math.round(1000.0 - 2.0 * d)) : 0;
        }, Set.of(), 0, 60, Walker.NODES_FAR);
        if (!ok) {
            stop(Chat.tr("terrafill.stop.tile_unreachable", Chat.hl(tileIndex + 1), Chat.pos(center)));
            return;
        }
        pathCells.clear();
        walker.collectPathCells(pathCells);
        state = State.TRAVEL;
        Chat.info("terrafill.tile.travel", Chat.hl(tileIndex + 1), Chat.pos(center));
    }

    private void travel(LocalPlayer p, ClientLevel level) {
        switch (walker.tick(p)) {
            case ARRIVED -> {
                pathCells.clear();
                state = State.FILL;
            }
            case STUCK -> {
                if (++replans > MAX_REPLANS) {
                    stop(Chat.tr("terrafill.stop.stuck", Chat.pos(p.blockPosition())));
                    return;
                }
                walker.clear();
                state = State.FILL; // fill() -> ensureTile() plant neu
            }
            case WALKING -> { }
        }
    }

    /** Weitermachen nach einem Stopp: Zustand frisch, alles Weitere kommt aus der Welt. */
    public boolean resume(ClientLevel level) {
        if (state == State.DONE) {
            Chat.info("terrafill.resume.done");
            return false;
        }
        running = true;
        state = State.FILL;
        idleTicks = 0;
        replans = 0;
        blockedUntil.clear();
        badSpots.clear();
        pathCells.clear();
        walker.clear();
        clearLocal();
        Walker.setWaterWalkable(!waterLid);
        skipped.clear();
        placedAtRetry = 0;
        approached = false;
        stuckEpisodes = 0;
        restockSetbacks = 0;
        restockAfterWalk = false;
        eatCooldown = 0;
        healPrevHp = -1.0f;
        lastHealth = -1.0f;
        fightCooldown = 0;
        if (tileIndex < 0) tileIndex = 0;
        if (tiles.isEmpty()) buildTiles(mc.player != null ? mc.player.blockPosition() : footprint.min());
        tileIndex = Math.min(tileIndex, tiles.size() - 1);
        enterTile(level);
        return true;
    }

    public void stop(Component reason) {
        running = false;
        LocalPlayer p = mc.player;
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        // Nie mit gedrueckter Benutzen-Taste aufhoeren, sonst isst der Spieler
        // nach dem Stopp von allein weiter.
        if (state == State.EAT) release();
        if (state == State.BREATHE) releaseJump();
        if (p != null) {
            walker.halt(p);
            restocker.abort(p);
            if (depot != null) depot.abort(p);
        }
        Chat.send(ChatFormatting.RED, reason);
    }

    private void finish() {
        state = State.DONE;
        running = false;
        long skippedAll = totalSkipped + skipped.size();
        if (skippedAll == 0) Chat.ok("terrafill.done", Chat.hl(placed), Chat.hl(tiles.size()));
        else Chat.warn("terrafill.done_skipped", Chat.hl(placed), Chat.hl(tiles.size()), Chat.hl(skippedAll));
    }

    // -------------------------------- Tick --------------------------------

    public void tick() {
        if (!running) return;
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null || mc.gameMode == null) {
            stop(Chat.tr("terrafill.stop.no_world"));
            return;
        }
        tick++;
        checkPending(level);

        if (p.isDeadOrDying()) {
            stop(Chat.tr("terrafill.stop.dead"));
            return;
        }
        if (!dimension.equals(level.dimension().identifier().toString())) {
            stop(Chat.tr("terrafill.stop.dimension"));
            return;
        }

        // Kampf hat Vorrang vor allem, auch vor den Abbruchgruenden: wer bei
        // niedrigem Leben stehen bleibt statt sich zu wehren, stirbt. Gekaempft
        // wird, sobald ein Gegner in Reichweite kommt oder Schaden ankommt.
        if (fightCooldown > 0) fightCooldown--;
        if (swordSlot >= 0 && state != State.FIGHT && fightCooldown == 0) {
            LivingEntity threat = nearestHostile(p, level);
            boolean engage = threat != null
                    && (p.distanceTo(threat) <= ENGAGE_RANGE || tookDamage(p));
            if (engage) beginFight(p);
        }
        lastHealth = p.getHealth();

        Component abort = safetyCheck(p);
        if (abort != null && state != State.FIGHT) {
            stop(Chat.tr("terrafill.stop.at", abort, Chat.pos(p.blockPosition())));
            return;
        }

        // Niedriges Leben ohne Gegner in der Naehe: nicht stoppen, sondern heilen.
        // Ein gestoppter Mod hilft einem AFK-Spieler nicht, ein wartender schon.
        if (isWorking() && p.getHealth() < HEALTH_FLOOR) {
            mc.gameMode.stopDestroyBlock();
            walker.halt(p);
            if (state == State.DEPOT) depot.interrupt(p);
            resumeState = state;
            state = State.HEAL;
            healTicks = 0;
            healPrevHp = -1.0f;
            Chat.warn("terrafill.heal.start", Chat.hl((int) p.getHealth()));
        }

        // Hunger: essen, bevor der Abbruch greift. Nicht mitten im Kampf oder mit
        // offener Kiste. Beim Heilen frueher, weil Regeneration Hunger >= 18 braucht.
        // Nach einem fehlgeschlagenen Essen eine Weile nicht wieder ansetzen.
        if (eatCooldown > 0) eatCooldown--;
        int eatAt = state == State.HEAL ? EAT_AT_HEAL : EAT_AT;
        if (eatCooldown == 0 && (isWorking() || state == State.HEAL)
                && p.getFoodData().getFoodLevel() <= eatAt && hasFood(p)) {
            mc.gameMode.stopDestroyBlock();
            walker.halt(p);
            if (state == State.DEPOT) depot.interrupt(p);
            eatReturn = state; // beim Heilen zurueck ins Heilen, sonst in den Bau-Zustand
            state = State.EAT;
            eatTicks = 0;
            wasEating = false;
        }

        // Beim Bauen in Seen: rechtzeitig Luft holen - nur nicht mit offener
        // Kiste, im Kampf oder mit dem Essen im Mund. Auch das Heilen wird
        // dafuer unterbrochen; die Lebenspruefung loest es danach neu aus.
        if ((state == State.FILL || state == State.WALK || state == State.HEAL || state == State.ESCAPE)
                && p.isUnderWater() && p.getAirSupply() < AIR_LOW) {
            beginBreathe(p, level);
            return;
        }
        // Deckel-Modus: ins Wasser gehoert er nicht. Ist er doch drin (Start im
        // Fluss, Rueckstoss), zuerst raus - gebaut wird erst wieder an Land.
        if (waterLid && (state == State.FILL || state == State.WALK) && p.isInWater()) {
            beginEscape(p, level);
            return;
        }

        switch (state) {
            case FILL -> fill(p, level);
            case WALK -> walk(p, level);
            case TRAVEL -> travel(p, level);
            case RESTOCK -> restock(p, level);
            case DEPOT -> depotTick(p, level);
            case BREATHE -> breathe(p, level);
            case ESCAPE -> escape(p, level);
            case FIGHT -> fight(p, level);
            case HEAL -> heal(p, level);
            case EAT -> eat(p);
            case DONE -> running = false;
        }
    }

    /**
     * Die eigentlichen Bau-Zustaende, aus denen Heilen und Essen starten duerfen.
     * Nicht beim Luftholen: wer da stehen bleibt, ertrinkt.
     */
    private boolean isWorking() {
        return state == State.FILL || state == State.WALK || state == State.DEPOT;
    }

    /** Gibt einen Abbruchgrund zurueck, oder null wenn alles in Ordnung ist. */
    private Component safetyCheck(LocalPlayer p) {
        // Ohne Schwert bleibt niedriges Leben ein Abbruchgrund - mehr als stehen
        // bleiben und melden kann der Mod dann nicht. Mit Schwert uebernehmen
        // Kampf und Heilen, siehe tick().
        if (swordSlot < 0 && p.getHealth() <= HEALTH_FLOOR) {
            return Chat.tr("terrafill.abort.health", Chat.hl((int) HEALTH_FLOOR));
        }
        if (p.getFoodData().getFoodLevel() <= 6 && !hasFood(p)) {
            return foodSlot < 0
                    ? Chat.tr("terrafill.abort.hunger_no_slot")
                    : Chat.tr("terrafill.abort.hunger_empty", Chat.hl(foodSlot + 1));
        }
        return null;
    }

    /** true, wenn seit dem letzten Tick Leben verloren ging. */
    private boolean tookDamage(LocalPlayer p) {
        return lastHealth >= 0.0f && p.getHealth() < lastHealth;
    }

    private void fill(LocalPlayer p, ClientLevel level) {
        if (!ensureTile(p, level)) return;
        if (!holdsBuildItem(p)) {
            beginRestock(p);
            return;
        }
        // Hochmauern laeuft: im Sprung setzen, sonst nichts.
        if (pillarTarget != null) {
            pillar(p, level);
            return;
        }
        // Im Wasser gebaut wird vom Grund aus: wer noch treibt (nach dem
        // Luftholen, nach einem Sprung ins Wasser), taucht erst runter, statt
        // minutenlang abzusinken und dabei halb schwebend zu setzen.
        if (p.isInWater() && !p.onGround()) {
            p.setDeltaMovement(0.0, Walker.DIVE, 0.0);
            return;
        }
        if (local) {
            rebuildRemainingLocal(level);
            if (remaining.isEmpty()) {
                // Ebene des Lochs fertig: kurz die Serverantwort abwarten
                // (holeLayers behaelt sie so lange), dann die naechste; ist
                // das Loch bis oben zu, zurueck zur Ebene der Kachel.
                if (holeLayers.isEmpty()) endLocal(level);
                return;
            }
        } else {
            rebuildRemaining(level);
            if (remaining.isEmpty()) {
                // Erst die Serverantwort auf die letzten Setzungen abwarten: nimmt er
                // eine zurueck, taucht die Zelle hier wieder auf, statt unter der
                // naechsten Ebene begraben zu werden.
                if (tick - lastPlaceTick < SETTLE_TICKS) return;
                int done = layerY;
                if (!findWork(level, false)) {
                    // Alle Ebenen durch. Bevor die Kachel als fertig gilt, noch
                    // einmal von unten durch alle Ebenen: vom Server
                    // zurueckgenommene Bloecke und uebersprungene Zellen kommen
                    // so noch einmal dran - solange seit der letzten Runde
                    // etwas gebaut wurde, sonst dreht er sich im Kreis.
                    if (placed > placedAtRetry) {
                        placedAtRetry = placed;
                        int wasSkipped = skipped.size();
                        skipped.clear();
                        badSpots.clear();
                        pending.clear();
                        layerY = layers.firstKey();
                        if (findWork(level, true)) {
                            Chat.info("terrafill.verify", Chat.hl(remaining.size()), Chat.hl(layerY), Chat.hl(wasSkipped));
                            return;
                        }
                    }
                    Chat.ok("terrafill.tile.done", Chat.hl(tileIndex + 1), Chat.hl(tiles.size()),
                            Chat.hl(placed - totalPlacedBefore), Chat.hl(reverted), Chat.hl(effectiveSpeed()));
                    nextTile(level);
                    return;
                }
                Chat.ok("terrafill.layer_done", Chat.hl(done), Chat.hl(placed), Chat.hl(total));
                badSpots.clear();
                blockedUntil.clear();
                return;
            }
        }

        int progress = placeReachable(p, level, null);
        if (progress > 0) {
            lastPlaceTick = tick;
            idleTicks = 0;
            replans = 0;
            badSpots.clear();
            approached = false;
            stuckEpisodes = 0;
            pillars = 0;
            return;
        }

        // Nachbarn auf Fusshoehe scheitern, wenn man nicht mittig im Feld steht:
        // dann erst zur Feldmitte, nicht gleich woandershin.
        if (skippedByBody && offCenter(p)) {
            BlockPos feet = p.blockPosition();
            walker.planTo(new Walker.Node(feet.getX(), feet.getY(), feet.getZ()));
            pathCells.clear();
            state = State.WALK;
            return;
        }

        // Blockierte Zellen in Reichweite: kurz warten, der Mob geht meist von allein.
        if (blockedInReach && ++idleTicks < IDLE_LIMIT) return;
        idleTicks = 0;
        planWalk(p, level);
    }

    private void walk(LocalPlayer p, ClientLevel level) {
        // Kurzer Gang zu einem Platz fuer die Shulkerbox: hier wird nichts
        // gesetzt (es ist ja kein Material da), nur gelaufen und danach der
        // Nachschub neu angesetzt.
        if (restockAfterWalk) {
            walkToRestock(p);
            return;
        }
        // Unterwegs setzen, was in Reichweite kommt - ausser auf dem Weg selbst.
        if (!holdsBuildItem(p)) {
            walker.halt(p);
            beginRestock(p);
            return;
        }
        if (local) rebuildRemainingLocal(level);
        else rebuildRemaining(level);
        // Im Wasser nur vom Grund aus setzen: wer gerade hoch- oder
        // runterschwimmt, hat keine verlaessliche Fusshoehe - lokal wuerde er
        // sonst eine Ebene zu hoch um sich herum auffuellen.
        boolean grounded = !p.isInWater() || p.onGround();
        if (grounded && placeReachable(p, level, pathCells) > 0) {
            lastPlaceTick = tick;
            replans = 0;
            approached = false;
            stuckEpisodes = 0;
            pillars = 0;
        }
        if (remaining.isEmpty()) {
            // Die Ebene ist unterwegs fertig geworden - der Rest des Weges ist umsonst.
            walker.halt(p);
            walker.clear();
            pathCells.clear();
            state = State.FILL;
            return;
        }

        switch (walker.tick(p)) {
            case ARRIVED -> {
                pathCells.clear();
                state = State.FILL;
            }
            case STUCK -> {
                if (++replans > MAX_REPLANS) {
                    // Festgefahren. Steht er in einem Loch, wird erst das
                    // zugeschuettet; kommt er auch im Loch nicht vom Fleck,
                    // einen Block unter sich setzen - das hebt ihn ueber jede
                    // Kante, an der der Walker scheitert. Auf offenem Gelaende
                    // hilft beides nicht: von vorn planen, vielleicht ist der
                    // Mob im Weg inzwischen weg; zweimal hintereinander ohne
                    // jeden Fortschritt muss der Spieler ran.
                    replans = 0;
                    walker.halt(p);
                    walker.clear();
                    pathCells.clear();
                    if (!local && beginLocal(p, level)) return;
                    if (local && beginPillar(p, level)) return;
                    if (++stuckEpisodes >= 2) {
                        stop(Chat.tr("terrafill.stop.stuck", Chat.pos(p.blockPosition())));
                        return;
                    }
                    badSpots.clear();
                    state = State.FILL;
                    return;
                }
                Chat.warn("terrafill.replan");
                planWalk(p, level);
            }
            case WALKING -> { }
        }
    }

    // ------------------------------ Lochmodus ------------------------------

    /**
     * In den Lochmodus wechseln, wenn der Spieler in einem Loch steht: einer
     * Senke, die auf seiner Fusshoehe rundherum von festen Bloecken (oder dem
     * Kachelrand) eingeschlossen ist, hoechstens HOLE_RADIUS weit. Das Loch
     * reicht nach oben, solange die Ebene darueber noch eingeschlossen ist;
     * gefuellt wird es Ebene fuer Ebene von unten, wie die Kachel selbst, nur
     * auf die Spalten des Lochs beschraenkt. Jede Stufe ist damit einen Block
     * hoch, und oben angekommen steht er auf offenem Gelaende. Auf offenem
     * Gelaende gibt es kein Loch - dann false, und der Aufrufer entscheidet.
     */
    private boolean beginLocal(LocalPlayer p, ClientLevel level) {
        BlockPos feet = p.blockPosition();
        if (local || !findHole(feet)) return false;
        local = true;
        mainLayerY = layerY;
        rebuildRemainingLocal(level);
        if (remaining.isEmpty()) {
            // Alles im Loch ist schon zu oder abgehakt - dann hilft das nicht.
            clearLocal();
            layerY = mainLayerY;
            rebuildRemaining(level);
            return false;
        }
        walker.halt(p);
        walker.clear();
        pathCells.clear();
        badSpots.clear();
        replans = 0;
        idleTicks = 0;
        state = State.FILL;
        Chat.warn("terrafill.local.start", Chat.pos(feet), Chat.hl(holeLayers.size()), Chat.hl(holeTop));
        return true;
    }

    /**
     * Das Loch um die Fuesse bestimmen. Ebene fuer Ebene ab Fusshoehe: die
     * Zellen der Ebene, die vom Fussfeld aus ueber Nachbarn erreichbar sind
     * (Flutung in der Ebene). Bleibt die Flutung innerhalb von HOLE_RADIUS,
     * ist die Ebene eingeschlossen und gehoert zum Loch; die erste Ebene, die
     * den Rand erreicht, ist offenes Gelaende und beendet das Loch. Weil jede
     * Spalte von ihrem Grund bis zur Oberkante offen ist (Blick von oben),
     * liegt die Flutung jeder Ebene in der darueber - das Loch ist also die
     * Flutung seiner obersten eingeschlossenen Ebene als Grundriss, mit allen
     * Zellen darunter. Steht er auf keiner Zelle des Auftrags, ist es kein Loch.
     */
    private boolean findHole(BlockPos feet) {
        holeColumns.clear();
        holeLayers.clear();
        int y0 = feet.getY();
        long[] feetLayer = layers.get(y0);
        if (feetLayer == null || !contains(feetLayer, feet.asLong())) return false;
        LongOpenHashSet pocket = null;
        int top = y0 - 1;
        for (int y = y0; y <= layers.lastKey(); y++) {
            long[] cells = layers.get(y);
            if (cells == null) break;
            LongOpenHashSet flood = floodLevel(cells, feet.getX(), y, feet.getZ());
            if (flood == null) break;
            pocket = flood;
            top = y;
        }
        if (pocket == null) return false;
        holeTop = top;
        for (long k : pocket) holeColumns.add(BlockPos.asLong(BlockPos.getX(k), 0, BlockPos.getZ(k)));
        for (Map.Entry<Integer, long[]> e : layers.headMap(top, true).entrySet()) {
            LongArrayList in = new LongArrayList();
            for (long k : e.getValue()) {
                if (holeColumns.contains(BlockPos.asLong(BlockPos.getX(k), 0, BlockPos.getZ(k)))) in.add(k);
            }
            if (!in.isEmpty()) holeLayers.put(e.getKey(), in.toLongArray());
        }
        return !holeLayers.isEmpty();
    }

    /**
     * Flutung einer Ebene vom Startfeld aus ueber waagerechte Nachbarn, die
     * Zellen der Ebene sind. null, sobald sie HOLE_RADIUS vom Start erreicht:
     * dann ist die Ebene nicht eingeschlossen.
     */
    private static LongOpenHashSet floodLevel(long[] cells, int sx, int y, int sz) {
        LongOpenHashSet open = new LongOpenHashSet(cells);
        long startKey = BlockPos.asLong(sx, y, sz);
        if (!open.contains(startKey)) return null;
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayList queue = new LongArrayList();
        seen.add(startKey);
        queue.add(startKey);
        for (int i = 0; i < queue.size(); i++) {
            long k = queue.getLong(i);
            int x = BlockPos.getX(k);
            int z = BlockPos.getZ(k);
            if (Math.abs(x - sx) >= HOLE_RADIUS || Math.abs(z - sz) >= HOLE_RADIUS) return null;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                long m = BlockPos.asLong(x + d.getStepX(), y, z + d.getStepZ());
                if (open.contains(m) && seen.add(m)) queue.add(m);
            }
        }
        return seen;
    }

    private static boolean contains(long[] cells, long key) {
        for (long k : cells) if (k == key) return true;
        return false;
    }

    /**
     * Offene Zellen der untersten Loch-Ebene, die noch Arbeit hat; layerY zeigt
     * darauf. Fertige Ebenen fallen aus holeLayers heraus (die Nachpruefung
     * macht die Kachel am Ende), die aktuelle aber erst nach SETTLE_TICKS Ruhe,
     * damit eine vom Server zurueckgenommene Setzung noch auftaucht - so lange
     * ist remaining leer, holeLayers aber nicht.
     */
    private void rebuildRemainingLocal(ClientLevel level) {
        remaining.clear();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        while (!holeLayers.isEmpty()) {
            int y = holeLayers.firstKey();
            layerY = y;
            for (long k : holeLayers.get(y)) {
                if (skipped.contains(k)) continue;
                m.set(BlockPos.getX(k), y, BlockPos.getZ(k));
                if (fillable(level, m)) remaining.add(m.immutable());
            }
            if (!remaining.isEmpty() || tick - lastPlaceTick < SETTLE_TICKS) return;
            holeLayers.remove(y);
        }
    }

    /** Das Loch ist zu (oder abgehakt): zurueck zur Ebene der Kachel. */
    private void endLocal(ClientLevel level) {
        clearLocal();
        layerY = mainLayerY;
        badSpots.clear();
        rebuildRemaining(level);
        Chat.ok("terrafill.local.done");
    }

    private void clearLocal() {
        local = false;
        holeColumns.clear();
        holeLayers.clear();
        pillarTarget = null;
        pillars = 0;
    }

    // ------------------------------ Hochmauern ------------------------------

    /**
     * Einen Block unter die eigenen Fuesse setzen: springen, und im Sprung das
     * Fussfeld ueber die Oberseite des Blocks darunter anklicken - wie ein
     * Spieler, der sich hochmauert. Nur wenn das Fussfeld eine Zelle des
     * Auftrags ist (in der Kachel, unter der Oberkante), sonst wuerde er sich
     * eine Saeule ins Gelaende bauen. Der Klick selbst kommt in pillar(),
     * sobald der Koerper das Feld freigegeben hat.
     */
    private boolean beginPillar(LocalPlayer p, ClientLevel level) {
        if (pillars >= PILLAR_MAX || !p.onGround() || p.isInWater() || !holdsBuildItem(p)) return false;
        BlockPos feet = p.blockPosition();
        long[] cells = layers.get(feet.getY());
        if (cells == null || !contains(cells, feet.asLong()) || !fillable(level, feet)) return false;
        if (!isSupport(level, feet.below())) return false;
        pillarTarget = feet.immutable();
        pillarTicks = 0;
        pillars++;
        walker.halt(p);
        walker.clear();
        pathCells.clear();
        state = State.FILL;
        p.jumpFromGround();
        Chat.warn("terrafill.pillar", Chat.pos(feet));
        return true;
    }

    /**
     * Im Sprung: sobald der Koerper das Fussfeld nicht mehr beruehrt, setzen.
     * Ein Sprung hebt die Fuesse gut einen Block, das reicht fuer drei, vier
     * Ticks. Wieder unten ohne Erfolg: aufgeben, der naechste Versuch kommt
     * ueber den normalen Weg.
     */
    private void pillar(LocalPlayer p, ClientLevel level) {
        BlockPos target = pillarTarget;
        if (++pillarTicks > PILLAR_TICKS || !fillable(level, target)) {
            pillarTarget = null;
            return;
        }
        if (p.getBoundingBox().inflate(0.05).intersects(new AABB(target))) return;
        if (tryPlace(p, level, target, reach(p)) == Placement.PLACED) {
            placed++;
            pending.put(target.asLong(), tick);
            lastPlaceTick = tick;
            p.swing(InteractionHand.MAIN_HAND);
            pillarTarget = null;
        }
    }

    private void beginRestock(LocalPlayer p) {
        mc.gameMode.stopDestroyBlock();
        walker.halt(p);
        walker.clear();
        pathCells.clear();
        pillarTarget = null;
        restockAfterWalk = false;
        restocker.begin();
        state = State.RESTOCK;
    }

    private void restock(LocalPlayer p, ClientLevel level) {
        restocker.tick(p, level);
        if (!restocker.isFinished()) return;
        if (restocker.failure() != null) {
            // Auffangbare Panne (Box weg, kein Platz) erst auffangen; nur wenn
            // das nicht geht, wirklich stoppen. Der Grund einer auffangbaren
            // Panne kommt roh und wird hier in den Abbruch-Rahmen gesetzt.
            if (restocker.recoverable() && recoverRestock(p, level)) return;
            stop(restocker.recoverable()
                    ? Chat.tr("terrafill.restock.abort", restocker.failure())
                    : restocker.failure());
            return;
        }
        restockSetbacks = 0;
        if (restocker.outOfMaterial()) {
            // Alles leer. Mit Depot: hin und nachfuellen. Ohne: Schluss.
            if (depotArea != null && beginDepot(p, level)) return;
            stop(Chat.tr("terrafill.restock.abort",
                    Chat.tr("terrafill.restock.empty", Component.translatable(buildItem.getDescriptionId()), Chat.pos(p.blockPosition()))));
            return;
        }
        state = State.FILL;
    }

    /**
     * Nachschub-Panne auffangen, statt den ganzen Auftrag zu beenden: eine Box
     * war weg oder es war kein Platz. Ist noch Material da (loser Stapel oder
     * volle Box), neu ansetzen - der naechste Zyklus nimmt die naechste Box; bei
     * Platzmangel vorher ein paar Felder zur Seite. Ist nichts mehr da, zum
     * Depot oder Schluss. Nach ein paar Pannen hintereinander ohne Fortschritt
     * wird wirklich gestoppt.
     */
    private boolean recoverRestock(LocalPlayer p, ClientLevel level) {
        if (!restocker.hasMaterial(p)) {
            // Box verloren und sonst nichts im Inventar - wie leer behandeln.
            if (depotArea != null && beginDepot(p, level)) {
                Chat.warn("terrafill.restock.retry_depot", restocker.failure());
                restockSetbacks = 0;
                return true;
            }
            return false; // nichts mehr da und kein Depot -> stop mit dem Grund
        }
        if (++restockSetbacks > RESTOCK_SETBACK_LIMIT) return false;
        Chat.warn("terrafill.restock.retry", restocker.failure(), Chat.hl(restockSetbacks));
        if (restocker.needSpace() && repositionForBox(p, level)) return true;
        beginRestock(p);
        return true;
    }

    /**
     * Bei Platzmangel zum naechsten begehbaren Feld laufen, neben dem ein freier
     * Platz mit festem Boden und Luft darueber ist - dort passt die Box. walk()
     * setzt danach den Nachschub neu an (`restockAfterWalk`). Kein solches Feld
     * erreichbar: false, dann wird an Ort und Stelle neu versucht.
     */
    private boolean repositionForBox(LocalPlayer p, ClientLevel level) {
        BlockPos feet = p.blockPosition();
        Region bounds = walkBounds(level, feet);
        Walker.Node start = new Walker.Node(feet.getX(), feet.getY(), feet.getZ());
        boolean ok = walker.plan(level, start, bounds,
                n -> boxSpotNear(level, n) ? 1 : 0, Set.of(start.key()), 0, 20, Walker.NODES_NEAR);
        if (!ok) return false;
        pathCells.clear();
        walker.collectPathCells(pathCells);
        restockAfterWalk = true;
        state = State.WALK;
        return true;
    }

    /** Hat dieses Feld einen waagerechten Nachbarn, auf den eine Shulkerbox passt (frei, Boden drunter, Luft drueber)? */
    private static boolean boxSpotNear(ClientLevel level, Walker.Node n) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int x = n.x() + d.getStepX();
            int z = n.z() + d.getStepZ();
            if (Walker.free(level, x, n.y(), z) && Walker.solid(level, x, n.y() - 1, z)
                    && Walker.free(level, x, n.y() + 1, z)) return true;
        }
        return false;
    }

    /** Kurzer Gang zu einem Platz mit Raum fuer die Box, dann den Nachschub neu ansetzen. */
    private void walkToRestock(LocalPlayer p) {
        switch (walker.tick(p)) {
            case ARRIVED, STUCK -> {
                restockAfterWalk = false;
                pathCells.clear();
                beginRestock(p);
            }
            case WALKING -> { }
        }
    }

    // -------------------------------- Depot --------------------------------

    /**
     * Zum Lager laufen und die Shulker fuellen. Der Walker darf dafuer weit
     * ueber den Bereich hinaus: die Grenzen umfassen Bereich, Spieler und
     * Lager. Liefert false, wenn im Lager kein Behaelter zu finden ist.
     */
    private boolean beginDepot(LocalPlayer p, ClientLevel level) {
        mc.gameMode.stopDestroyBlock();
        walker.halt(p);
        walker.clear();
        pathCells.clear();
        depot = new Depot(mc, buildItem, depotArea);
        depot.setSlot(slot);
        Region bounds = walkBounds(level, p.blockPosition())
                .including(depotArea.min(), 3)
                .including(depotArea.max(), 3);
        bounds = new Region(bounds.minX(), Math.max(level.getMinY(), bounds.minY()), bounds.minZ(),
                bounds.maxX(), Math.min(level.getMaxY(), bounds.maxY()), bounds.maxZ());
        depot.begin(p, level, bounds);
        state = State.DEPOT;
        return true;
    }

    private void depotTick(LocalPlayer p, ClientLevel level) {
        depot.tick(p, level);
        if (!depot.isFinished()) return;
        if (depot.failure() != null) {
            stop(depot.failure());
            return;
        }
        // Zurueck an die Arbeit - den Weg findet planWalk von selbst.
        badSpots.clear();
        approached = false;
        state = State.FILL;
    }

    // ------------------------------ Luft holen ------------------------------

    /**
     * Luft holen. Ist ueber dem Kopf nur Wasser und dann Luft, wird einfach
     * senkrecht hochgeschwommen - Sprungtaste halten, wie ein Spieler es tut;
     * an der Oberflaeche bleibt der Kopf damit von selbst draussen. Nur unter
     * einer Decke (Hoehlensee) wird zum naechsten Feld mit Luft gelaufen.
     * Gibt es auch das nicht: Stopp, bevor es ernst wird.
     */
    private void beginBreathe(LocalPlayer p, ClientLevel level) {
        mc.gameMode.stopDestroyBlock();
        pathCells.clear();
        walker.halt(p);
        walker.clear();
        breatheTicks = 0;
        BlockPos feet = p.blockPosition();
        if (airAbove(level, feet)) {
            swimUp = true;
            state = State.BREATHE;
            return;
        }
        swimUp = false;
        Walker.Node start = new Walker.Node(feet.getX(), feet.getY(), feet.getZ());
        boolean ok = walker.plan(level, start, walkBounds(level, feet),
                n -> Walker.headInAir(level, n.x(), n.y(), n.z()) ? 1 : 0, Set.of());
        if (!ok) {
            stop(Chat.tr("terrafill.stop.no_air", Chat.pos(feet)));
            return;
        }
        walker.collectPathCells(pathCells);
        state = State.BREATHE;
    }

    /** Ueber dem Kopf nur Wasser, dann Luft - kein Block dazwischen? */
    private static boolean airAbove(ClientLevel level, BlockPos feet) {
        BlockPos.MutableBlockPos m = feet.mutable();
        for (int y = feet.getY() + 1; y <= feet.getY() + 24; y++) {
            m.setY(y);
            BlockState st = level.getBlockState(m);
            if (!st.getCollisionShape(level, m).isEmpty()) return false;
            if (st.getFluidState().isEmpty()) return true;
        }
        return false;
    }

    private void breathe(LocalPlayer p, ClientLevel level) {
        breatheTicks++;
        if (swimUp) {
            mc.options.keyJump.setDown(true);
            p.setDeltaMovement(0.0, p.getDeltaMovement().y, 0.0);
            if (!p.isUnderWater() && p.getAirSupply() >= p.getMaxAirSupply()) {
                mc.options.keyJump.setDown(false);
                replans = 0;
                state = State.FILL;
                return;
            }
            // Nach 20 Sekunden immer noch unter Wasser: da ist doch etwas im Weg.
            if (breatheTicks > 400 && p.isUnderWater()) {
                mc.options.keyJump.setDown(false);
                stop(Chat.tr("terrafill.stop.no_air", Chat.pos(p.blockPosition())));
            }
            return;
        }

        if (walker.isActive()) {
            switch (walker.tick(p)) {
                case STUCK -> {
                    if (++replans > MAX_REPLANS) {
                        stop(Chat.tr("terrafill.stop.stuck", Chat.pos(p.blockPosition())));
                        return;
                    }
                    beginBreathe(p, level);
                }
                case ARRIVED, WALKING -> { }
            }
            return;
        }
        // Angekommen: stehen bleiben, bis die Luft wieder voll ist.
        if (p.getAirSupply() >= p.getMaxAirSupply()) {
            pathCells.clear();
            replans = 0;
            state = State.FILL;
        }
    }

    // ----------------------------- Raus aus dem Wasser -----------------------------

    /**
     * Deckel-Modus, aber im Wasser: zum naechsten begehbaren Feld ohne Wasser
     * laufen - dafuer darf der Walker ausnahmsweise durchs Wasser. Gibt es
     * keinen Ausstieg (Ufer ueberall hoeher als eine Stufe), Stopp mit
     * Position; der Deckel wird vom Ufer aus gebaut, nicht aus dem Fluss.
     */
    private void beginEscape(LocalPlayer p, ClientLevel level) {
        mc.gameMode.stopDestroyBlock();
        walker.halt(p);
        walker.clear();
        pathCells.clear();
        BlockPos feet = p.blockPosition();
        Walker.Node start = new Walker.Node(feet.getX(), feet.getY(), feet.getZ());
        Walker.setWaterWalkable(true);
        boolean ok = walker.plan(level, start, walkBounds(level, feet),
                n -> Walker.inWater(level, n.x(), n.y(), n.z()) ? 0 : 1, Set.of(), 0, 0, Walker.NODES_NEAR);
        if (!ok) {
            Walker.setWaterWalkable(false);
            stop(Chat.tr("terrafill.stop.in_water", Chat.pos(feet)));
            return;
        }
        walker.collectPathCells(pathCells);
        state = State.ESCAPE;
        Chat.warn("terrafill.escape", Chat.pos(feet));
    }

    private void escape(LocalPlayer p, ClientLevel level) {
        switch (walker.tick(p)) {
            case ARRIVED -> {
                Walker.setWaterWalkable(false);
                pathCells.clear();
                replans = 0;
                state = State.FILL;
            }
            case STUCK -> {
                if (++replans > MAX_REPLANS) {
                    Walker.setWaterWalkable(false);
                    stop(Chat.tr("terrafill.stop.in_water", Chat.pos(p.blockPosition())));
                    return;
                }
                beginEscape(p, level);
            }
            case WALKING -> { }
        }
    }

    /** Sprungtaste loslassen, falls wir sie halten - bei jedem Verlassen von BREATHE. */
    private void releaseJump() {
        mc.options.keyJump.setDown(false);
    }

    // -------------------------------- Kampf --------------------------------

    /**
     * In den Kampf wechseln, aus jedem Zustand. Eine offene Kiste wird zugemacht
     * und der Nachschub danach an derselben Stelle fortgesetzt, Essen wird
     * losgelassen. Was gerade lief, wird nach dem Kampf wieder aufgenommen.
     */
    private void beginFight(LocalPlayer p) {
        mc.gameMode.stopDestroyBlock();
        walker.halt(p);
        switch (state) {
            case DEPOT -> {
                depot.interrupt(p);
                resumeState = State.DEPOT;
                fightReturn = State.DEPOT;
            }
            case RESTOCK -> {
                restocker.interrupt(p);
                resumeState = State.RESTOCK;
                fightReturn = State.RESTOCK;
            }
            // Angefangenes Essen wird nicht fortgesetzt - der Hunger-Check
            // loest es bei Bedarf neu aus. Wer gerade heilte, heilt danach weiter.
            case EAT -> {
                release();
                fightReturn = eatReturn;
            }
            case HEAL -> fightReturn = State.HEAL;
            case BREATHE -> {
                // Der Weg zur Luft ist nach dem Kampf wahrscheinlich hinfaellig;
                // die Luftpruefung im Tick loest ihn bei Bedarf neu aus.
                releaseJump();
                walker.clear();
                pathCells.clear();
                resumeState = State.FILL;
                fightReturn = State.FILL;
            }
            default -> {
                resumeState = state;
                fightReturn = state;
            }
        }
        state = State.FIGHT;
        fightTicks = 0;
        noTargetTicks = 0;
        Chat.warn("terrafill.fight.start");
    }

    /**
     * Zugeschlagen wird nur bei vollem Angriffsbalken, sonst waere der Schaden
     * ein Bruchteil - genau wie von Hand. Wer ausser Reichweite steht, wird
     * Schritt fuer Schritt angegangen, jeder Schritt nur auf gepruefen Boden.
     * Creeper werden nie angelaufen. Niedriges Leben bricht den Kampf NICHT ab:
     * stehen bleiben waere immer schlechter.
     */
    private void fight(LocalPlayer p, ClientLevel level) {
        if (++fightTicks > FIGHT_MAX_TICKS) {
            fightCooldown = 100;
            endFight(p, "terrafill.fight.timeout");
            return;
        }

        LivingEntity target = nearestHostile(p, level);
        if (target == null) {
            walker.halt(p);
            if (++noTargetTicks > FIGHT_GIVEUP_TICKS) endFight(p, null);
            return;
        }
        noTargetTicks = 0;
        selectSlot(p, swordSlot);
        Restocker.lookAt(p, target.getEyePosition());

        if (p.distanceTo(target) > ATTACK_RANGE) {
            approach(p, level, target);
            return;
        }
        walker.halt(p);

        if (p.getAttackStrengthScale(0.0f) >= 1.0f) {
            mc.gameMode.attack(p, target);
            p.swing(InteractionHand.MAIN_HAND);
        }
    }

    private void endFight(LocalPlayer p, String msgKey) {
        walker.halt(p);
        state = fightReturn;
        noTargetTicks = 0;
        if (msgKey != null) Chat.warn(msgKey);
    }

    /**
     * Einen Schritt auf den Gegner zu, aber nur, wenn der Block unter dem
     * naechsten Schritt traegt - in ein Loch oder ins Wasser fuehrt das nie.
     */
    private void approach(LocalPlayer p, ClientLevel level, LivingEntity target) {
        if (target instanceof Creeper) {
            walker.halt(p);
            return;
        }
        double dx = target.getX() - p.getX();
        double dz = target.getZ() - p.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.01) return;

        BlockPos next = BlockPos.containing(p.getX() + dx / dist, p.getY(), p.getZ() + dz / dist);
        if (!Walker.solid(level, next.getX(), next.getY() - 1, next.getZ())) {
            walker.halt(p);
            return;
        }
        double v = Math.min(APPROACH_SPEED, dist);
        p.setDeltaMovement(dx / dist * v, p.getDeltaMovement().y, dz / dist * v);
    }

    private LivingEntity nearestHostile(LocalPlayer p, ClientLevel level) {
        AABB box = p.getBoundingBox().inflate(TARGET_SEARCH);
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        // Nur mit Sichtlinie: durch eine Wand hindurch kann weder er uns noch wir
        // ihn treffen, und ein Angriff durch die Wand koennte ein Mensch auch nicht.
        for (Entity e : level.getEntities(p, box, x -> x instanceof Enemy && x.isAlive() && p.hasLineOfSight(x))) {
            if (!(e instanceof LivingEntity le)) continue;
            double d = p.distanceToSqr(e);
            if (d < bestDist) {
                bestDist = d;
                best = le;
            }
        }
        return best;
    }

    // -------------------------------- Heilen --------------------------------

    /**
     * Bei niedrigem Leben: sich einmauern und regenerieren lassen. Ein
     * 1x1-Kasten aus dem Baublock (Fuss- und Kopfring plus Deckel) haelt
     * Nahkaempfer und die meisten Geschosse ab - viel besser als offen
     * stehenzubleiben, wie es vorher war. Essen (fuer die Regeneration ab
     * Hunger 18) uebernimmt der Tick-Check. Taucht ein Gegner auf, uebernimmt
     * der Kampf und kommt danach hierher zurueck.
     */
    private void heal(LocalPlayer p, ClientLevel level) {
        walker.halt(p);

        if (p.getHealth() >= HEAL_UNTIL) {
            healPrevHp = -1.0f;
            state = resumeState;
            Chat.ok("terrafill.heal.done", Chat.hl((int) p.getHealth()));
            return;
        }

        // Einmauern, solange er auf dem Boden steht und Baumaterial hat. Nur
        // Fehlendes in Reichweite wird gesetzt; steht die Wand schon (Gelaende),
        // passiert nichts.
        if (holdsBuildItem(p) && p.onGround() && !p.isInWater()) shelter(p, level);

        // Sinkt das Leben trotz Wand weiter und ist kein Gegner da, tut etwas
        // weh, das eine Wand nicht aufhaelt (Lava, Ertrinken, Ersticken, Gift) -
        // dann bringt Warten nichts. Erst nach ein paar Ticks pruefen, damit ein
        // einzelner Treffer, bevor die Wand steht, nicht sofort abbricht.
        if (healTicks > 40 && healPrevHp >= 0.0f && p.getHealth() < healPrevHp - 0.5f
                && p.getHealth() < 6.0f && nearestHostile(p, level) == null) {
            stop(Chat.tr("terrafill.stop.losing_health", Chat.hl((int) p.getHealth())));
            return;
        }
        healPrevHp = p.getHealth();

        if (++healTicks > HEAL_MAX_TICKS) {
            stop(Chat.tr("terrafill.stop.no_regen", Chat.hl((int) p.getHealth())));
        }
    }

    /**
     * Die Felder eines 1x1-Schutzkastens um den Spieler setzen: der Fuss- und
     * der Kopfring (je vier) und der Deckel darueber. Hoechstens ein paar pro
     * Tick, wie beim normalen Setzen; was schon steht oder im eigenen Koerper
     * liegt, wird ausgelassen.
     */
    private void shelter(LocalPlayer p, ClientLevel level) {
        BlockPos feet = p.blockPosition();
        double reach = reach(p);
        AABB body = p.getBoundingBox().inflate(0.05);
        int done = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            done += shelterPlace(p, level, feet.relative(d), reach, body);
            done += shelterPlace(p, level, feet.above().relative(d), reach, body);
            if (done >= 3) break;
        }
        if (done < 3) done += shelterPlace(p, level, feet.above(2), reach, body);
        if (done > 0) p.swing(InteractionHand.MAIN_HAND);
    }

    private int shelterPlace(LocalPlayer p, ClientLevel level, BlockPos c, double reach, AABB body) {
        if (!holdsBuildItem(p)) return 0;
        BlockState st = level.getBlockState(c);
        if (!st.isAir() && !st.canBeReplaced()) return 0;     // steht schon etwas
        if (body.intersects(new AABB(c))) return 0;           // nie ins eigene Feld
        return tryPlace(p, level, c, reach) == Placement.PLACED ? 1 : 0;
    }

    // -------------------------------- Essen --------------------------------

    /** Liegt im Essen-Slot etwas Essbares? Geprueft ueber die FOOD-Komponente. */
    private boolean hasFood(LocalPlayer p) {
        if (foodSlot < 0) return false;
        ItemStack st = p.getInventory().getItem(foodSlot);
        return !st.isEmpty() && st.has(DataComponents.FOOD);
    }

    /**
     * Essen heisst in Vanilla: Rechtsklick 32 Ticks lang HALTEN. Minecraft.tick
     * prueft jeden Tick "isUsingItem && !keyUse.isDown -> releaseUsingItem", ein
     * blosser useItem-Aufruf wuerde also im naechsten Tick wieder abgebrochen.
     * Deshalb wird die Benutzen-Taste fuer die Dauer des Essens gedrueckt gehalten
     * und danach wieder losgelassen - in jedem Ausgang dieser Methode.
     */
    private void eat(LocalPlayer p) {
        eatTicks++;

        if (!hasFood(p)) {
            release();
            state = eatReturn;
            return;
        }

        selectSlot(p, foodSlot);
        // Blick in den Himmel: dort ist garantiert nichts, womit ein Rechtsklick
        // interagieren koennte.
        p.setXRot(-90.0f);

        if (p.isUsingItem()) {
            wasEating = true;
            mc.options.keyUse.setDown(true);
            return;
        }
        if (wasEating) {
            // Fertig gegessen. Reicht es noch nicht, loest der Tick-Check
            // einfach die naechste Runde aus.
            release();
            state = eatReturn;
            return;
        }
        if (eatTicks > EAT_MAX_TICKS) {
            // Das Essen will auf diesem Server nicht anfangen. Nicht den ganzen
            // Auftrag abbrechen (das war der aergerliche Hard-Stop): zurueck an
            // die Arbeit, kurze Sperre, damit nicht sofort wieder EAT triggert.
            // Wird der Hunger wirklich kritisch und ist dann nichts Essbares da,
            // greift immer noch der Abbruch in safetyCheck.
            release();
            eatCooldown = EAT_COOLDOWN;
            state = eatReturn;
            Chat.warn("terrafill.eat.stuck", Chat.hl(foodSlot + 1));
            return;
        }
        mc.options.keyUse.setDown(true);
        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
    }

    private void release() {
        mc.options.keyUse.setDown(false);
        if (mc.player != null && mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
    }

    // ------------------------------- Ebenen -------------------------------

    /** Von der aktuellen Ebene aus (einschliesslich oder nicht) die naechste mit Arbeit suchen. */
    private boolean findWork(ClientLevel level, boolean includeCurrent) {
        for (int y : layers.keySet()) {
            if (y < layerY || (!includeCurrent && y == layerY)) continue;
            layerY = y;
            rebuildRemaining(level);
            if (!remaining.isEmpty()) return true;
        }
        remaining.clear();
        return false;
    }

    /**
     * Offene Zellen der Ebene frisch aus der Welt lesen. Jeden Tick, damit vom
     * Server zurueckgenommene Setzungen von selbst wieder auftauchen und
     * Fremdes (nachgerutschter Kies, gefrorenes Wasser) als erledigt gilt.
     */
    private void rebuildRemaining(ClientLevel level) {
        remaining.clear();
        long[] cells = layers.get(layerY);
        if (cells == null) return;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (long k : cells) {
            if (skipped.contains(k)) continue;
            m.set(BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k));
            if (fillable(level, m)) remaining.add(m.immutable());
        }
    }

    // ------------------------------- Setzen -------------------------------

    private boolean holdsBuildItem(LocalPlayer p) {
        return p.getInventory().getItem(slot).is(buildItem);
    }

    /**
     * Vom Server zurueckgenommene Setzungen erkennen. Der Client setzt den
     * Block als Vorhersage; bestaetigt der Server nicht, nimmt er ihn nach
     * seiner Antwort wieder weg. Taucht ein frisch gesetzter Block also wieder
     * als fuellbar auf, war der Server zu langsam oder hat abgelehnt - dann
     * einen Gang runterschalten. Die Zelle selbst kommt ueber die frische
     * Abtastung von allein wieder dran.
     */
    private void checkPending(ClientLevel level) {
        if (pending.isEmpty()) return;
        if (tick % 5 != 0) return;
        var it = pending.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            int age = tick - e.getValue();
            if (age < CONFIRM_TICKS) continue;
            BlockPos pos = BlockPos.of(e.getKey());
            if (level.isLoaded(pos) && fillable(level, pos)) {
                reverted++;
                lastRevertTick = tick;
                if (revertBackoff < speed - 1) revertBackoff++;
            }
            it.remove();
        }
        if (revertBackoff > 0 && tick - lastRevertTick > RECOVER_TICKS) {
            revertBackoff--;
            lastRevertTick = tick; // naechste Stufe erst nach weiteren RECOVER_TICKS
        }
    }

    /** Eingestelltes Tempo minus das, was der Server zuletzt nicht mitgemacht hat. */
    private int effectiveSpeed() {
        return Math.max(1, speed - revertBackoff);
    }

    /**
     * Reichweite vom Auge zur Klickflaeche. Der Server prueft gegen das
     * Attribut plus einen Block Reserve; die Einstellung darf bis knapp darunter
     * gehen, ein Attribut-Effekt (Reichweiten-Trank) hebt beides an.
     */
    private double reach(LocalPlayer p) {
        return Math.min(reach, p.blockInteractionRange() + 0.9);
    }

    /**
     * Setzt bis zu speed Bloecke in Reichweite, naechste zuerst, und schlaegt
     * nebenbei hoechstens einen weichen Block weg. Liefert die Zahl der
     * Fortschritte; 0 heisst: von hier aus geht gerade nichts.
     */
    private int placeReachable(LocalPlayer p, ClientLevel level, Set<Long> exclude) {
        blockedInReach = false;
        skippedByBody = false;
        double reach = reach(p);
        double reachSq = reach * reach;
        Vec3 eye = p.getEyePosition();
        AABB body = p.getBoundingBox().inflate(0.05);

        List<BlockPos> near = new ArrayList<>();
        double quick = (reach + 1.0) * (reach + 1.0);
        BlockPos feet = p.blockPosition();
        int feetY = feet.getY();
        for (BlockPos c : remaining) {
            // Lokal nie ueber Fusshoehe setzen - sonst mauert er sich ein.
            if (local && c.getY() > feetY) continue;
            // Und nie in die eigene Spalte ueber dem Kopf: unter Wasser waere
            // das der Deckel, der das Hochschwimmen verhindert.
            if (c.getY() > feetY && c.getX() == feet.getX() && c.getZ() == feet.getZ()) continue;
            double dx = c.getX() + 0.5 - eye.x;
            double dz = c.getZ() + 0.5 - eye.z;
            if (dx * dx + dz * dz > quick) continue;
            if (new AABB(c).distanceToSqr(eye) > reachSq) continue;
            near.add(c);
        }
        Comparator<BlockPos> byDistance = Comparator.comparingDouble(c -> Vec3.atCenterOf(c).distanceToSqr(eye));
        near.sort(byDistance);

        int placedNow = 0;
        boolean shoved = false;
        BlockPos softTarget = null;
        for (BlockPos c : near) {
            if (placedNow >= effectiveSpeed()) break;
            long key = c.asLong();
            if (exclude != null && exclude.contains(key)) continue;
            Integer until = blockedUntil.get(key);
            if (until != null) {
                if (tick < until) {
                    blockedInReach = true;
                    continue;
                }
                blockedUntil.remove(key);
            }
            if (body.intersects(new AABB(c))) {
                skippedByBody = true;
                continue;
            }
            BlockState st = level.getBlockState(c);
            if (soft(st, level, c)) {
                if (softTarget == null) softTarget = c;
                continue;
            }
            if (!holdsBuildItem(p)) break; // Stapel mittendrin leer, naechster Tick holt Nachschub
            switch (tryPlace(p, level, c, reach)) {
                case PLACED -> {
                    placedNow++;
                    placed++;
                    pending.put(key, tick);
                }
                case FAILED -> {
                    blockedUntil.put(key, tick + BACKOFF_TICKS);
                    blockedInReach = true;
                    // Steht ein Tier im Feld, kriegt es einen Schubs mit dem Block
                    // in der Hand - es rennt weg, und in einer Sekunde ist das
                    // Feld frei. Hoechstens eins pro Tick.
                    if (!shoved) {
                        LivingEntity animal = blockingAnimal(p, level, c);
                        if (animal != null && p.distanceTo(animal) <= SHOVE_RANGE) {
                            shove(p, animal);
                            shoved = true;
                        }
                    }
                }
                case NO_SUPPORT -> { }
            }
        }

        int progress = placedNow;
        if (softTarget != null) {
            breakSoft(p, level, softTarget);
            progress++;
        }
        if (placedNow > 0) p.swing(InteractionHand.MAIN_HAND);
        return progress;
    }

    /**
     * Setzt den Baublock auf cell, indem eine angrenzende Klickflaeche
     * angeklickt wird - wie Vanilla beim Rechtsklick. Der angeklickte Block
     * muss in Reichweite sein, das ist genau das, was der Server prueft.
     */
    private Placement tryPlace(LocalPlayer p, ClientLevel level, BlockPos cell, double reach) {
        Vec3 eye = p.getEyePosition();
        Direction d = supportFrom(level, eye, cell, reach * reach);
        if (d == null) return Placement.NO_SUPPORT;
        BlockPos s = cell.relative(d);
        Direction face = d.getOpposite();
        Vec3 hit = Vec3.atCenterOf(s).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        selectSlot(p, slot);
        Restocker.lookAt(p, hit);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, s, false));
        return level.getBlockState(cell).is(buildBlock) ? Placement.PLACED : Placement.FAILED;
    }

    /**
     * Erste Richtung aus SUPPORT_ORDER, in der eine Klickflaeche in Reichweite
     * des Auges liegt, sonst null. Dieselbe Frage stellt die Standortsuche,
     * damit sie nichts verspricht, was das Setzen nachher nicht halten kann.
     */
    private static Direction supportFrom(ClientLevel level, Vec3 eye, BlockPos cell, double reachSq) {
        return supportFrom(level, eye, cell, reachSq, null);
    }

    /** Wie oben, mit Merkzettel fuer die Klickflaechen (0 unbekannt, 1 ja, 2 nein) - die Standortsuche fragt dieselben Bloecke hundertfach. */
    private static Direction supportFrom(ClientLevel level, Vec3 eye, BlockPos cell, double reachSq,
                                         Long2ByteOpenHashMap cache) {
        for (Direction d : SUPPORT_ORDER) {
            BlockPos s = cell.relative(d);
            boolean support;
            if (cache == null) {
                support = isSupport(level, s);
            } else {
                long key = s.asLong();
                byte known = cache.get(key);
                if (known == 0) {
                    support = isSupport(level, s);
                    cache.put(key, (byte) (support ? 1 : 2));
                } else {
                    support = known == 1;
                }
            }
            if (!support) continue;
            if (new AABB(s).distanceToSqr(eye) > reachSq) continue;
            return d;
        }
        return null;
    }

    /**
     * Taugt der Block als Klickflaeche? Alles Feste, das beim Rechtsklick nichts
     * tut. Bloecke mit Block-Entity (Kisten, Oefen, Schilder, die eigene
     * Shulkerbox) und die bekannten Rechtsklick-Bloecke ohne Entity wuerden
     * stattdessen aufgehen, statt dass gesetzt wird.
     */
    private static boolean isSupport(ClientLevel level, BlockPos s) {
        BlockState st = level.getBlockState(s);
        if (st.isAir() || st.canBeReplaced() || st.hasBlockEntity()) return false;
        Block b = st.getBlock();
        return !(b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock
                || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof CraftingTableBlock
                || b instanceof NoteBlock || b instanceof AnvilBlock || b instanceof FlowerPotBlock);
    }

    /**
     * Weiche Bloecke (Haerte 0 bis 0.2, keine Kollision) wegschlagen. Bei Haerte
     * 0 ist das ein Tick. Vanilla bricht ohne gedrueckte Angriffstaste jeden
     * Tick ab, was aber nur einmal die Fortschrittsanzeige zuruecksetzt: danach
     * bleibt das Ziel gemerkt und der Fortschritt laeuft weiter.
     */
    private void breakSoft(LocalPlayer p, ClientLevel level, BlockPos c) {
        selectSlot(p, slot);
        Restocker.lookAt(p, Vec3.atCenterOf(c));
        if (mc.gameMode.continueDestroyBlock(c, Restocker.faceToward(p.getEyePosition(), c))) {
            p.swing(InteractionHand.MAIN_HAND);
        }
    }

    /**
     * Ein Tier, das im Feld steht und das Setzen blockiert. Keine Spieler,
     * keine Dorfbewohner (der Golem wuerde es uebel nehmen), keine Golems, keine
     * Gegner - um die kuemmert sich der Kampf.
     */
    private static LivingEntity blockingAnimal(LocalPlayer p, ClientLevel level, BlockPos cell) {
        for (Entity e : level.getEntities(p, new AABB(cell), x -> x instanceof LivingEntity && x.isAlive()
                && !(x instanceof Player) && !(x instanceof Enemy)
                && !(x instanceof AbstractVillager) && !(x instanceof IronGolem))) {
            return (LivingEntity) e;
        }
        return null;
    }

    /** Ein Schlag mit dem, was in der Hand ist. Tiere geraten davon in Panik und rennen weg. */
    private void shove(LocalPlayer p, LivingEntity animal) {
        Restocker.lookAt(p, animal.getEyePosition());
        mc.gameMode.attack(p, animal);
        p.swing(InteractionHand.MAIN_HAND);
    }

    private static boolean offCenter(LocalPlayer p) {
        double dx = p.getX() - (Math.floor(p.getX()) + 0.5);
        double dz = p.getZ() - (Math.floor(p.getZ()) + 0.5);
        return dx * dx + dz * dz > 0.2 * 0.2;
    }

    // -------------------------------- Laufen --------------------------------

    /**
     * Einen Standort suchen, von dem aus offene Zellen baubar sind, und
     * hinlaufen. Der jetzige Standort hat sich gerade als nutzlos erwiesen und
     * wird gemieden, bis wieder etwas gesetzt wurde.
     */
    private void planWalk(LocalPlayer p, ClientLevel level) {
        if (remaining.isEmpty()) {
            state = State.FILL; // fill() schaltet auf die naechste Ebene
            return;
        }
        BlockPos feet = p.blockPosition();
        badSpots.add(feet.asLong());

        double reach = reach(p);
        float eyeHeight = p.getEyeHeight();
        Region bounds = walkBounds(level, feet);
        Walker.Node start = new Walker.Node(feet.getX(), feet.getY(), feet.getZ());
        Long2ByteOpenHashMap supportCache = new Long2ByteOpenHashMap();
        // Die offenen Zellen als Menge, dazu ein Grobraster (8x8 Spalten): die
        // Bewertung fragt fuer jedes Feld der Suche 121 Zellen ab, und die
        // meisten Felder liegen weit weg von allem Offenen.
        LongOpenHashSet open = new LongOpenHashSet(remaining.size());
        LongOpenHashSet coarse = new LongOpenHashSet();
        for (BlockPos c : remaining) {
            open.add(c.asLong());
            coarse.add(coarseKey(c.getX(), c.getZ()));
        }
        boolean ok = walker.plan(level, start, bounds,
                n -> coverage(level, n, open, coarse, reach, eyeHeight, supportCache), badSpots);
        if (!ok) {
            // Kein Standort erreichbar. Erst so nah wie moeglich an die offenen
            // Zellen heran (vielleicht reicht es dann), dann das Loch
            // zuschuetten, in dem er steht - falls er in einem steht. Sonst
            // werden die Zellen uebersprungen; ein tiefer Schacht darf nicht
            // den ganzen Auftrag anhalten, die Nachpruefung am Kachelende
            // versucht sie noch einmal.
            if (!local) {
                if (!approached && planApproach(p, level, bounds)) {
                    approached = true;
                    return;
                }
                if (!beginLocal(p, level)) skipRemaining(p);
                return;
            }
            // Im Loch: bleibt das eigene Fussfeld, dann hochmauern; sonst die
            // Loch-Ebene abhaken, die naechste kommt von allein.
            if (remaining.contains(feet) && beginPillar(p, level)) return;
            skipRemaining(p);
            return;
        }
        pathCells.clear();
        walker.collectPathCells(pathCells);
        state = State.WALK;
    }

    /**
     * Die offenen Zellen als unerreichbar abhaken. Sie zaehlen nicht mehr als
     * offen; am Ende steht im Chat, wie viele es waren. Einmal je Ebene eine
     * Meldung, damit ein tiefer Schacht nicht den Chat flutet.
     */
    private void skipRemaining(LocalPlayer p) {
        if (remaining.isEmpty()) return;
        for (BlockPos c : remaining) skipped.add(c.asLong());
        BlockPos example = remaining.get(0);
        int n = remaining.size();
        remaining.clear();
        walker.halt(p);
        walker.clear();
        pathCells.clear();
        badSpots.clear();
        state = State.FILL;
        if (tick - lastSkipMessageTick > 100) {
            lastSkipMessageTick = tick;
            Chat.warn("terrafill.skipped", Chat.hl(n), Chat.pos(example), Chat.hl(skipped.size()));
        }
    }

    /**
     * An die naechstgelegene offene Zelle heranlaufen, so weit die Oberflaeche
     * traegt - ohne Abzug fuer die Weglaenge, dafuer mit langer Suche. Ein
     * Loch, das zu tief zum Reinspringen ist, wird so wenigstens vom Rand aus
     * angegangen, und der lokale Modus fuellt es dann von dort nach unten.
     */
    private boolean planApproach(LocalPlayer p, ClientLevel level, Region bounds) {
        BlockPos feet = p.blockPosition();
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (BlockPos c : remaining) {
            double d = c.distSqr(feet);
            if (d < best) {
                best = d;
                nearest = c;
            }
        }
        if (nearest == null) return false;
        Vec3 target = Vec3.atCenterOf(nearest);
        Walker.Node start = new Walker.Node(feet.getX(), feet.getY(), feet.getZ());
        Set<Long> avoid = new HashSet<>(badSpots);
        avoid.add(start.key());
        double now = target.distanceTo(Vec3.atCenterOf(feet));

        boolean ok = walker.plan(level, start, bounds, n -> {
            double d = target.distanceTo(new Vec3(n.x() + 0.5, n.y() + 0.5, n.z() + 0.5));
            // Nur Felder, die naeher dran sind als jetzt, sind einen Weg wert.
            return d < now - 1.0 ? Math.max(1, (int) Math.round(1000.0 - 2.0 * d)) : 0;
        }, avoid, 0, 60, Walker.NODES_FAR);
        if (!ok) return false;
        pathCells.clear();
        walker.collectPathCells(pathCells);
        state = State.WALK;
        Chat.warn("terrafill.approach", Chat.pos(nearest));
        return true;
    }

    /** Der Bereich mit Rand, und der Spieler liegt auf jeden Fall mit drin - er darf auch von weiter weg starten. */
    private Region walkBounds(ClientLevel level, BlockPos feet) {
        Region r = region.grow(WALK_MARGIN, WALK_VERTICAL).including(feet, 2);
        return new Region(r.minX(), Math.max(level.getMinY(), r.minY()), r.minZ(),
                r.maxX(), Math.min(level.getMaxY(), r.maxY()), r.maxZ());
    }

    /** Grobraster fuer die Standortsuche: 8x8 Spalten je Feld, als BlockPos-Schluessel mit y = 0. */
    private static long coarseKey(int x, int z) {
        return BlockPos.asLong(x >> 3, 0, z >> 3);
    }

    /**
     * Wie viele offene Zellen der Ebene von diesem Standort aus baubar waeren:
     * mit Klickflaeche in Reichweite, und nicht das eigene Fuss- oder Kopffeld.
     * Die Zellen liegen alle auf der aktuellen Ebene (auch im Lochmodus),
     * deshalb reicht ein flacher Umkreis. Vorher zwei billige Ausschluesse:
     * die Ebene senkrecht ausser Reichweite, oder im eigenen Grobraster und
     * den acht Nachbarn nichts Offenes - COVER_RADIUS ist kleiner als ein
     * Rasterfeld, weiter reicht der Umkreis also nie.
     */
    private int coverage(ClientLevel level, Walker.Node n, LongOpenHashSet open, LongOpenHashSet coarse,
                         double reach, float eyeHeight, Long2ByteOpenHashMap supportCache) {
        int up = (int) Math.ceil(reach);
        if (layerY < n.y() - up || layerY > n.y() + 3 + up) return 0;
        int cx = n.x() >> 3;
        int cz = n.z() >> 3;
        boolean any = false;
        for (int i = -1; i <= 1 && !any; i++) {
            for (int j = -1; j <= 1; j++) {
                if (coarse.contains(BlockPos.asLong(cx + i, 0, cz + j))) {
                    any = true;
                    break;
                }
            }
        }
        if (!any) return 0;

        Vec3 eye = new Vec3(n.x() + 0.5, n.y() + eyeHeight, n.z() + 0.5);
        double reachSq = reach * reach;
        int count = 0;
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        for (int dx = -COVER_RADIUS; dx <= COVER_RADIUS; dx++) {
            for (int dz = -COVER_RADIUS; dz <= COVER_RADIUS; dz++) {
                int x = n.x() + dx;
                int z = n.z() + dz;
                if (!open.contains(BlockPos.asLong(x, layerY, z))) continue;
                if (dx == 0 && dz == 0 && (layerY == n.y() || layerY == n.y() + 1)) continue;
                if (supportFrom(level, eye, cell.set(x, layerY, z), reachSq, supportCache) != null) count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------

    private static void selectSlot(LocalPlayer p, int slot) {
        if (slot < 0 || slot >= Inventory.SELECTION_SIZE) return;
        if (p.getInventory().getSelectedSlot() != slot) p.getInventory().setSelectedSlot(slot);
    }
}
