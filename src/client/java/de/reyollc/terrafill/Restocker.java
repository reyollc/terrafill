package de.reyollc.terrafill;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nachschub fuer den Bau-Slot.
 *
 * Erst wird ein loser Stapel des Baublocks aus dem Inventar in den Slot
 * getauscht (SWAP, wie die Zahlentaste ueber einem Inventarplatz). Gibt es
 * keinen mehr, kommt der Shulker-Zyklus: Box neben den Spieler setzen, oeffnen,
 * Stapel per Shift-Klick ins Inventar holen, schliessen, abbauen, aufsammeln.
 * Alles ueber useItemOn, handleContainerInput und continueDestroyBlock, also
 * ueber dieselben Wege wie ein Rechtsklick oder ein Klick im Menue.
 *
 * Ein Platz im Inventar bleibt immer frei, damit die abgebaute Box nachher
 * wieder eingesteckt werden kann. Deshalb: mindestens zwei freie Plaetze
 * mitbringen, sonst kann nichts geholt werden.
 */
final class Restocker {

    private enum Step { SWAP, PLACE, OPEN, MOVE, CLOSE, BREAK, RECLAIM }

    private static final Logger LOG = LoggerFactory.getLogger("terrafill");
    /** Slots 0..26 eines Shulker-Menues sind die Kiste, alles danach der Spieler. */
    private static final int SHULKER_SLOTS = 27;
    private static final int TIMEOUT_TICKS = 100;
    private static final int MOVES_PER_TICK = 4;
    /** Aufsammeln: ab hier zum Item hinlaufen, ab da aufgeben - Items liegen fuenf Minuten, das reicht zum Stoppen. */
    private static final int PICKUP_WALK_AFTER = 15;
    private static final int PICKUP_TIMEOUT = 300;
    private static final double DROP_SEARCH = 8.0;

    private final Minecraft mc;
    private final Item item;
    private final Walker walker = new Walker();
    private int slot;

    private Step step = Step.SWAP;
    private int wait;
    private BlockPos boxPos;
    private int boxSlot = -1;
    private int boxesBefore;
    private int moved;
    private boolean giveBack;     // naechstes MOVE legt einen Stapel zurueck statt welche zu holen
    private boolean gaveBack;     // einmal pro Zyklus, sonst Pingpong
    private BlockPos dropTarget;
    private boolean finished = true;
    private Component failure;
    private boolean outOfMaterial;
    private boolean recoverable;   // Panne, die FillTask auffangen kann (Box weg, kein Platz), kein harter Stopp
    private boolean needSpace;     // Grund war Platzmangel - vor dem naechsten Versuch umsetzen

    Restocker(Minecraft mc, Item item) {
        this.mc = mc;
        this.item = item;
    }

    void setSlot(int slot) { this.slot = slot; }

    boolean isFinished() { return finished; }

    /** Kein Baublock mehr - weder lose noch in einer Shulkerbox. */
    boolean outOfMaterial() { return outOfMaterial; }

    /** Grund des Scheiterns, oder null wenn der Zyklus gut ausgegangen ist. */
    Component failure() { return failure; }

    /** Die Panne ist auffangbar (Box weg oder kein Platz) - FillTask muss nicht gleich stoppen. */
    boolean recoverable() { return recoverable; }

    /** Der auffangbare Grund war Platzmangel: vor dem naechsten Versuch lohnt ein Schritt zur Seite. */
    boolean needSpace() { return needSpace; }

    /** Ist ueberhaupt noch Baublock da - lose oder in einer Shulkerbox? */
    boolean hasMaterial(LocalPlayer p) { return bestLooseStack(p) >= 0 || findBox(p) >= 0; }

    void begin() {
        step = Step.SWAP;
        wait = 0;
        boxPos = null;
        boxSlot = -1;
        moved = 0;
        giveBack = false;
        gaveBack = false;
        dropTarget = null;
        walker.clear();
        finished = false;
        failure = null;
        outOfMaterial = false;
        recoverable = false;
        needSpace = false;
    }

    /** Beim Stopp von aussen: offene Kiste zumachen, und sagen, wo eine Box noch steht oder liegt. */
    void abort(LocalPlayer p) {
        if (finished) return;
        walker.halt(p);
        if (p.containerMenu instanceof ShulkerBoxMenu) p.closeContainer();
        if (boxPos != null && mc.level != null) {
            if (mc.level.getBlockState(boxPos).getBlock() instanceof ShulkerBoxBlock) {
                Chat.warn("terrafill.restock.left_behind", Chat.pos(boxPos));
            } else if (step == Step.RECLAIM && countBoxes(p) < boxesBefore) {
                Chat.warn("terrafill.restock.drop_left", Chat.pos(dropTarget != null ? dropTarget : boxPos));
            }
        }
        finished = true;
    }

    /**
     * Fuer den Kampf unterbrechen: Kiste zu, aber gesetzt lassen. Danach geht
     * es dort weiter, wo es sinnvoll ist - steht die Box noch, wird sie wieder
     * geoeffnet; ist sie schon abgebaut, wird sie eingesammelt; war sie noch
     * nicht gesetzt, faengt der Zyklus von vorn an.
     */
    void interrupt(LocalPlayer p) {
        if (finished) return;
        mc.gameMode.stopDestroyBlock();
        if (p.containerMenu instanceof ShulkerBoxMenu) p.closeContainer();
        if (boxPos == null) {
            step = Step.SWAP;
        } else if (mc.level != null && mc.level.getBlockState(boxPos).getBlock() instanceof ShulkerBoxBlock) {
            step = Step.OPEN;
        } else {
            step = Step.RECLAIM;
        }
        wait = 0;
    }

    void tick(LocalPlayer p, ClientLevel level) {
        if (finished) return;
        switch (step) {
            case SWAP -> swap(p);
            case PLACE -> place(p, level);
            case OPEN -> open(p, level);
            case MOVE -> move(p);
            case CLOSE -> close(p);
            case BREAK -> breakBox(p, level);
            case RECLAIM -> reclaim(p, level);
        }
    }

    // ------------------------------------------------------------------

    private void swap(LocalPlayer p) {
        if (p.getInventory().getItem(slot).is(item)) {
            finish(p);
            return;
        }
        if (++wait > TIMEOUT_TICKS) {
            fail(p, Chat.tr("terrafill.restock.swap_failed", Chat.hl(slot + 1)));
            return;
        }
        // Zwischen zwei Versuchen ein paar Ticks Luft fuer die Serverantwort.
        if (wait % 5 != 1) return;

        int loose = bestLooseStack(p);
        if (loose >= 0) {
            swapIntoHotbar(p, loose, slot);
            return;
        }

        int box = findBox(p);
        if (box < 0) {
            // Nichts mehr da - weder lose noch in einer Box. Ob es damit vorbei
            // ist oder ein Depot-Gang folgt, entscheidet FillTask.
            outOfMaterial = true;
            finish(p);
            return;
        }
        boxesBefore = countBoxes(p);
        if (box < Inventory.SELECTION_SIZE) {
            boxSlot = box;
        } else {
            // Der Bau-Slot ist gerade leer - da hinein, dann kann sie gesetzt werden.
            swapIntoHotbar(p, box, slot);
            boxSlot = slot;
        }
        step = Step.PLACE;
        wait = 0;
    }

    /** Groesster loser Stapel des Baublocks ausserhalb des Bau-Slots, -1 wenn keiner. */
    private int bestLooseStack(LocalPlayer p) {
        int best = -1;
        int bestCount = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (i == slot) continue;
            ItemStack st = p.getInventory().getItem(i);
            if (st.is(item) && st.getCount() > bestCount) {
                bestCount = st.getCount();
                best = i;
            }
        }
        return best;
    }

    /**
     * Shulker mit dem Baublock drin. Genommen wird die mit den wenigsten
     * passenden Stapeln, damit Kisten nacheinander leer werden statt alle
     * gleichzeitig halbvoll zu sein.
     */
    private int findBox(LocalPlayer p) {
        int best = -1;
        int bestStacks = Integer.MAX_VALUE;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!isBox(st)) continue;
            int stacks = stacksOfItem(st, item);
            if (stacks > 0 && stacks < bestStacks) {
                bestStacks = stacks;
                best = i;
            }
        }
        return best;
    }

    /** Wie viele Stapel des Baublocks in einem Shulker-Item stecken, gelesen aus der CONTAINER-Komponente. */
    static int stacksOfItem(ItemStack box, Item item) {
        ItemContainerContents c = box.get(DataComponents.CONTAINER);
        if (c == null) return 0;
        return (int) c.nonEmptyItemCopyStream().filter(s -> s.is(item)).count();
    }

    static boolean isBox(ItemStack st) {
        return !st.isEmpty() && Block.byItem(st.getItem()) instanceof ShulkerBoxBlock;
    }

    static int countBoxes(LocalPlayer p) {
        int n = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (isBox(p.getInventory().getItem(i))) n++;
        }
        return n;
    }

    static int freeSlots(LocalPlayer p) {
        int n = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (p.getInventory().getItem(i).isEmpty()) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------

    private void place(LocalPlayer p, ClientLevel level) {
        if (boxPos != null && level.getBlockState(boxPos).getBlock() instanceof ShulkerBoxBlock) {
            step = Step.OPEN;
            wait = 0;
            return;
        }
        if (++wait > TIMEOUT_TICKS) {
            fail(p, Chat.tr("terrafill.restock.place_failed"));
            return;
        }
        if (wait % 10 != 1) return;

        if (!isBox(p.getInventory().getItem(boxSlot))) {
            // Der Tausch ist (noch) nicht angekommen - naechster Versuch in 10 Ticks.
            return;
        }
        BlockPos spot = findSpot(p, level);
        if (spot == null) {
            setback(p, Chat.tr("terrafill.restock.no_space"), true);
            return;
        }
        // Auf die Oberseite des Blocks darunter klicken: so zeigt die Box nach
        // oben und geht nach oben auf, wo Platz ist.
        BlockPos under = spot.below();
        Vec3 hit = Vec3.atCenterOf(under).add(0.0, 0.5, 0.0);
        selectSlot(p, boxSlot);
        lookAt(p, hit);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, under, false));
        p.swing(InteractionHand.MAIN_HAND);
        boxPos = spot.immutable();
    }

    /**
     * Ein Feld direkt neben dem Spieler: frei, mit festem Block darunter, Luft
     * darueber (die Box klappt nach oben auf), in Reichweite und nicht im
     * Spieler selbst. Unter den Kandidaten gewinnt der sicherste: das
     * abgebaute Item huepft bis zu einem halben Block weit, also lieber ein
     * Platz, um den herum Boden ist und kein Wasser, das es wegtraegt. Dann
     * naeher vor weiter, Fusshoehe vor tiefer vor hoeher.
     */
    static BlockPos findSpot(LocalPlayer p, ClientLevel level) {
        BlockPos feet = p.blockPosition();
        AABB body = p.getBoundingBox().inflate(0.1);
        Vec3 eye = p.getEyePosition();
        double reach = p.blockInteractionRange();
        int[] dys = { 0, -1, 1 };
        BlockPos best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int radius = 1; radius <= 2; radius++) {
            for (int dy : dys) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                        BlockPos c = feet.offset(dx, dy, dz);
                        if (!level.getBlockState(c).canBeReplaced()) continue;
                        if (body.intersects(new AABB(c))) continue;
                        BlockPos under = c.below();
                        BlockState us = level.getBlockState(under);
                        if (us.canBeReplaced() || us.hasBlockEntity()) continue;
                        if (!us.isFaceSturdy(level, under, Direction.UP)) continue;
                        if (!Walker.free(level, c.getX(), c.getY() + 1, c.getZ())) continue;
                        if (new AABB(under).distanceToSqr(eye) > reach * reach) continue;

                        int score = 10 * rimAround(level, under) - 20 * fluidAround(level, c)
                                - 3 * radius - 2 * Math.abs(dy);
                        if (score > bestScore) {
                            bestScore = score;
                            best = c;
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Wie viele der vier Nachbarn des Bodenblocks tragen - da kann das Item nicht runterfallen. */
    private static int rimAround(ClientLevel level, BlockPos under) {
        int n = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos s = under.relative(d);
            if (Walker.solid(level, s.getX(), s.getY(), s.getZ())) n++;
        }
        return n;
    }

    /** Wasser oder Lava neben dem Feld oder neben seinem Boden - das traegt ein Item weg. */
    private static int fluidAround(ClientLevel level, BlockPos c) {
        int n = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!level.getBlockState(c.relative(d)).getFluidState().isEmpty()) n++;
            if (!level.getBlockState(c.below().relative(d)).getFluidState().isEmpty()) n++;
        }
        return n;
    }

    private void open(LocalPlayer p, ClientLevel level) {
        if (p.containerMenu instanceof ShulkerBoxMenu) {
            step = Step.MOVE;
            wait = 0;
            return;
        }
        if (++wait > TIMEOUT_TICKS) {
            fail(p, Chat.tr("terrafill.restock.open_failed", Chat.pos(boxPos)));
            return;
        }
        if (!(level.getBlockState(boxPos).getBlock() instanceof ShulkerBoxBlock)) {
            setback(p, Chat.tr("terrafill.restock.gone"), false);
            return;
        }
        // Nur alle 10 Ticks anklopfen, sonst prasseln Oeffnen-Pakete auf den Server.
        if (wait % 10 != 1) return;

        Vec3 hit = Vec3.atCenterOf(boxPos).add(0.0, 0.5, 0.0);
        lookAt(p, hit);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, boxPos, false));
    }

    /**
     * Stapel des Baublocks per Shift-Klick aus der Kiste holen, mehrere pro
     * Tick. Ein Inventarplatz bleibt frei fuer die Box selbst.
     */
    private void move(LocalPlayer p) {
        if (!(p.containerMenu instanceof ShulkerBoxMenu menu)) {
            // Menue ist von selbst zugegangen - dann eben ohne weitere Stapel weiter.
            step = Step.BREAK;
            wait = 0;
            return;
        }
        if (++wait > TIMEOUT_TICKS * 3) {
            step = Step.CLOSE;
            wait = 0;
            return;
        }
        if (giveBack) {
            // Der letzte freie Platz ist inzwischen belegt (Kelp, Blumen,
            // Mob-Drops): einen Stapel zurueck in die Kiste, damit die Box
            // nachher wieder ins Inventar passt.
            giveBack = false;
            int i = playerSlotWithItem(menu, p);
            if (i >= 0) {
                mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, p);
                moved--;
            }
            step = Step.CLOSE;
            wait = 0;
            return;
        }

        int now = 0;
        while (now < MOVES_PER_TICK && freeSlots(p) > 1) {
            int i = boxSlotWithItem(menu);
            if (i < 0) break;
            mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, p);
            now++;
            moved++;
            // Die Vorhersage sollte den Stapel schon verschoben haben. Wenn nicht,
            // hat der Klick nichts bewirkt - dann nicht weiter draufklicken.
            if (menu.getSlot(i).hasItem() && menu.getSlot(i).getItem().is(item)) break;
        }
        if (now > 0) return; // naechsten Tick weiter

        // Nichts geholt, und der Grund ist das Inventar: dann hilft auch keine
        // andere Kiste. Sonst (Kiste wider Erwarten leer) einfach zumachen.
        if (moved == 0 && freeSlots(p) <= 1) {
            fail(p, Chat.tr("terrafill.restock.no_room", Chat.pos(boxPos)));
            return;
        }
        step = Step.CLOSE;
        wait = 0;
    }

    private int boxSlotWithItem(ShulkerBoxMenu menu) {
        for (int i = 0; i < SHULKER_SLOTS && i < menu.slots.size(); i++) {
            if (menu.getSlot(i).getItem().is(item)) return i;
        }
        return -1;
    }

    /** Menueposition des kleinsten Baublock-Stapels im Spielerinventar, ausser dem Bau-Slot selbst. -1 wenn keiner. */
    private int playerSlotWithItem(ShulkerBoxMenu menu, LocalPlayer p) {
        int best = -1;
        int bestCount = Integer.MAX_VALUE;
        for (int i = SHULKER_SLOTS; i < menu.slots.size(); i++) {
            Slot s = menu.getSlot(i);
            if (s.container != p.getInventory() || s.getContainerSlot() == slot) continue;
            ItemStack st = s.getItem();
            if (st.is(item) && st.getCount() < bestCount) {
                bestCount = st.getCount();
                best = i;
            }
        }
        return best;
    }

    private void close(LocalPlayer p) {
        if (p.containerMenu instanceof ShulkerBoxMenu) {
            LOG.info("TerraFill: Kiste zu nach {} Stapeln", moved);
            p.closeContainer();
        }
        step = Step.BREAK;
        wait = 0;
    }

    private void breakBox(LocalPlayer p, ClientLevel level) {
        BlockState st = level.getBlockState(boxPos);
        if (!(st.getBlock() instanceof ShulkerBoxBlock)) {
            mc.gameMode.stopDestroyBlock();
            step = Step.RECLAIM;
            wait = 0;
            dropTarget = null;
            return;
        }
        // Erst abbauen, wenn die Box hinterher auch wieder reinpasst. Eine
        // stehende Box ist sicher, ein liegendes Item ist nach fuenf Minuten weg.
        if (freeSlots(p) == 0) {
            mc.gameMode.stopDestroyBlock();
            if (gaveBack) {
                fail(p, Chat.tr("terrafill.restock.no_room", Chat.pos(boxPos)));
                return;
            }
            gaveBack = true;
            giveBack = true;
            step = Step.OPEN;
            wait = 0;
            return;
        }
        if (++wait > TIMEOUT_TICKS * 3) {
            mc.gameMode.stopDestroyBlock();
            fail(p, Chat.tr("terrafill.restock.break_failed", Chat.pos(boxPos)));
            return;
        }
        // Das schnellste Werkzeug in der Hotbar, sonst die blosse Hand (3 Sekunden).
        selectSlot(p, bestToolSlot(p, st, slot));
        lookAt(p, Vec3.atCenterOf(boxPos));
        if (mc.gameMode.continueDestroyBlock(boxPos, faceToward(p.getEyePosition(), boxPos))) {
            p.swing(InteractionHand.MAIN_HAND);
        }
    }

    /**
     * Das schnellste Werkzeug in der Hotbar, sonst der Bau-Slot. Nur echte
     * Werkzeuge (schneller als die Hand) kommen in Frage - mit dem Schwert
     * Bloecke abzubauen kostet doppelte Haltbarkeit.
     */
    static int bestToolSlot(LocalPlayer p, BlockState st, int slot) {
        int best = slot;
        float bestSpeed = 1.0f;
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
            ItemStack tool = p.getInventory().getItem(i);
            if (tool.isEmpty() || isBox(tool)) continue;
            float speed = tool.getDestroySpeed(st);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = i;
            }
        }
        return best;
    }

    /** Auf das Aufsammeln warten; Items liegen fuenf Minuten, laenger als drei Sekunden lohnt das Warten nicht. */
    /**
     * Die abgebaute Box wieder einstecken. Das Item huepft beim Abbauen bis zu
     * einem halben Block weit und liegt dann gern knapp ausserhalb der
     * Aufsammel-Reichweite (ein Block um den Spieler). Deshalb: kurz die
     * Aufsammel-Sperre abwarten, dann zum Item hinlaufen und darauf stehen
     * bleiben. Ohne Box geht es nicht weiter - lieber stoppen, als sie liegen
     * zu lassen.
     */
    private void reclaim(LocalPlayer p, ClientLevel level) {
        if (countBoxes(p) >= boxesBefore) {
            walker.halt(p);
            finish(p);
            return;
        }
        wait++;
        ItemEntity drop = nearestBoxDrop(p, level);
        if (drop == null) {
            // Noch nicht vom Server gekommen, oder schon aufgesammelt und der
            // Inventarstand noch unterwegs. Weg ist es erst, wenn es lange fehlt.
            if (wait > TIMEOUT_TICKS) {
                setback(p, Chat.tr("terrafill.restock.drop_lost", Chat.pos(boxPos)), false);
            }
            return;
        }
        if (wait > PICKUP_TIMEOUT) {
            setback(p, Chat.tr("terrafill.restock.not_picked_up", Chat.pos(drop.blockPosition())), false);
            return;
        }
        if (wait < PICKUP_WALK_AFTER) return;

        BlockPos at = drop.blockPosition();
        if (!at.equals(dropTarget) || !walker.isActive()) {
            dropTarget = at.immutable();
            walker.planTo(new Walker.Node(at.getX(), at.getY(), at.getZ()));
        }
        walker.tick(p);
    }

    /** Das naechste am Boden liegende Shulker-Item, oder null. */
    static ItemEntity nearestBoxDrop(LocalPlayer p, ClientLevel level) {
        ItemEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : level.getEntities(p, p.getBoundingBox().inflate(DROP_SEARCH),
                x -> x instanceof ItemEntity ie && isBox(ie.getItem()))) {
            double d = p.distanceToSqr(e);
            if (d < bestDist) {
                bestDist = d;
                best = (ItemEntity) e;
            }
        }
        return best;
    }

    private void finish(LocalPlayer p) {
        if (moved > 0) {
            Chat.ok("terrafill.restock.done", Chat.hl(moved), Component.translatable(item.getDescriptionId()));
        }
        selectSlot(p, slot);
        finished = true;
    }

    /** Zyklus abbrechen: Kiste zu, Box bleibt stehen, der Grund geht an FillTask.stop. */
    private void fail(LocalPlayer p, Component reason) {
        mc.gameMode.stopDestroyBlock();
        walker.halt(p);
        if (p.containerMenu instanceof ShulkerBoxMenu) p.closeContainer();
        selectSlot(p, slot);
        failure = Chat.tr("terrafill.restock.abort", reason);
        finished = true;
    }

    /**
     * Auffangbare Panne: eine Box ist weg oder es war kein Platz. Wie fail(),
     * aber ohne harten Stopp - FillTask setzt neu an, solange noch Material da
     * ist. Der Grund bleibt roh (ohne "Abbruch"-Rahmen), FillTask meldet selbst.
     */
    private void setback(LocalPlayer p, Component reason, boolean space) {
        mc.gameMode.stopDestroyBlock();
        walker.halt(p);
        if (p.containerMenu instanceof ShulkerBoxMenu) p.closeContainer();
        selectSlot(p, slot);
        failure = reason;
        recoverable = true;
        needSpace = space;
        finished = true;
    }

    // ------------------------------------------------------------------

    /** Inventarplatz invIndex mit Hotbar-Slot hotbar tauschen - der Vanilla-Klick "Zahlentaste ueber Slot". */
    static void swapIntoHotbar(LocalPlayer p, int invIndex, int hotbar) {
        int menuSlot = menuSlotOf(p.inventoryMenu, p, invIndex);
        if (menuSlot < 0) return;
        Minecraft.getInstance().gameMode.handleContainerInput(p.inventoryMenu.containerId, menuSlot, hotbar, ContainerInput.SWAP, p);
    }

    /** Menueposition eines Inventarplatzes, ohne Annahmen ueber die Slot-Anordnung. */
    static int menuSlotOf(AbstractContainerMenu menu, LocalPlayer p, int invIndex) {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.getSlot(i);
            if (s.container == p.getInventory() && s.getContainerSlot() == invIndex) return i;
        }
        return -1;
    }

    static void selectSlot(LocalPlayer p, int slot) {
        if (slot < 0 || slot >= Inventory.SELECTION_SIZE) return;
        if (p.getInventory().getSelectedSlot() != slot) p.getInventory().setSelectedSlot(slot);
    }

    /** Ohne Direction.getNearest gebaut, weil die Signatur sich zwischen Versionen bewegt hat. */
    static Direction faceToward(Vec3 eye, BlockPos pos) {
        double dx = eye.x - (pos.getX() + 0.5);
        double dy = eye.y - (pos.getY() + 0.5);
        double dz = eye.z - (pos.getZ() + 0.5);
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ax >= ay && ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
        if (ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    static void lookAt(LocalPlayer p, Vec3 target) {
        Vec3 eye = p.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        p.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        p.setXRot((float) (-Math.toDegrees(Math.atan2(dy, flat))));
    }
}
