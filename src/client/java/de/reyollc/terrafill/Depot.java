package de.reyollc.terrafill;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Der Gang zum Depot: Sind alle Shulker leer, laeuft er zum gemerkten
 * Lagerbereich (z.B. die Kistenwand eines Cobblestone-Generators), sucht dort
 * alle Kisten, Faesser und sonstigen Behaelter und fuellt die Shulker wieder
 * auf. Eine Shulkerbox laesst sich nicht im Inventar befuellen, deshalb geht
 * es im Kreis: Kiste auf, Stapel per Shift-Klick ins Inventar, Kiste zu, Box
 * neben sich setzen, Box auf, Stapel hinein, Box zu - und wieder zur Kiste,
 * bis die Box voll ist; dann Box abbauen, einstecken, naechste Box. Leere
 * Kisten werden uebersprungen, die naechste ist dran. Am Ende geht es zurueck
 * an die Arbeit, den Weg findet der normale Walker.
 *
 * Dieselben Wege wie beim Nachschub: useItemOn, handleContainerInput,
 * continueDestroyBlock. Ein Inventarplatz bleibt immer frei fuer die Box.
 */
final class Depot {

    private enum Step { GO, SCAN, OPEN_CHEST, TAKE, CLOSE_CHEST, PLACE_BOX, OPEN_BOX, PUT, CLOSE_BOX, BREAK_BOX, RECLAIM_BOX }

    private static final int SHULKER_SLOTS = 27;
    private static final int TIMEOUT_TICKS = 100;
    private static final int MOVES_PER_TICK = 4;
    private static final int MAX_REPLANS = 3;
    private static final int PICKUP_WALK_AFTER = 15;
    private static final int PICKUP_TIMEOUT = 300;
    /** Groesser darf der Lagerbereich nicht sein - mehr Bloecke abzusuchen lohnt nicht. */
    static final long MAX_DEPOT_VOLUME = 262_144L;

    private final Minecraft mc;
    private final Item item;
    private final Region area;
    private final Walker walker = new Walker();
    private int slot;

    private final List<BlockPos> chests = new ArrayList<>();
    private Step step = Step.GO;
    private Step afterGo = Step.OPEN_CHEST;
    private BlockPos target;
    private int wait;
    private int chestIndex;
    private Region bounds;
    private int replans;
    private BlockPos boxPos;
    private int boxSlot = -1;
    private int boxesBefore;
    private BlockPos dropTarget;
    private int takenRound;
    private boolean chestEmpty;
    private boolean noRoom;
    private boolean boxFull;
    private int filledBoxes;
    private int stacksPut;
    private boolean finished = true;
    private Component failure;

    Depot(Minecraft mc, Item item, Region area) {
        this.mc = mc;
        this.item = item;
        this.area = area;
    }

    void setSlot(int slot) { this.slot = slot; }

    boolean isFinished() { return finished; }

    Component failure() { return failure; }

    boolean hasArea() { return area != null; }

    /** Alle Behaelter im Lagerbereich, die gerade geladen sind - vom Spieler aus nach Naehe sortiert. */
    static List<BlockPos> findContainers(ClientLevel level, Region area, BlockPos from) {
        List<BlockPos> out = new ArrayList<>();
        if (area == null || area.volume() > MAX_DEPOT_VOLUME) return out;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = area.minX(); x <= area.maxX(); x++) {
            for (int z = area.minZ(); z <= area.maxZ(); z++) {
                for (int y = area.minY(); y <= area.maxY(); y++) {
                    m.set(x, y, z);
                    if (!level.isLoaded(m)) continue;
                    if (level.getBlockEntity(m) instanceof Container) out.add(m.immutable());
                }
            }
        }
        out.sort(Comparator.comparingDouble(c -> c.distSqr(from)));
        return out;
    }

    /** Den Gang beginnen. Ist das Lager nicht geladen, wird erst hingelaufen und dort gesucht. */
    void begin(LocalPlayer p, ClientLevel level, Region bounds) {
        this.bounds = bounds;
        chests.clear();
        chestIndex = 0;
        replans = 0;
        boxPos = null;
        boxSlot = -1;
        dropTarget = null;
        takenRound = 0;
        chestEmpty = false;
        noRoom = false;
        boxFull = false;
        filledBoxes = 0;
        stacksPut = 0;
        walker.clear();
        finished = false;
        failure = null;

        BlockPos center = new BlockPos((area.minX() + area.maxX()) / 2, area.minY(), (area.minZ() + area.maxZ()) / 2);
        if (!level.isLoaded(center)) {
            // Das Lager liegt ausserhalb der Sichtweite: erst hinlaufen, dann
            // die Behaelter suchen - Block-Entities gibt es nur in geladenen Chunks.
            goTo(center, Step.SCAN);
            Chat.info("terrafill.depot.far", Chat.pos(center));
            return;
        }
        scan(p, level);
    }

    /** Behaelter im Lager suchen und zur ersten Kiste gehen. false, wenn keiner da ist. */
    private boolean scan(LocalPlayer p, ClientLevel level) {
        chests.clear();
        chests.addAll(findContainers(level, area, p.blockPosition()));
        if (chests.isEmpty()) {
            fail(p, Chat.tr("terrafill.depot.no_chests", Chat.pos(area.min())));
            return false;
        }
        chestIndex = 0;
        goTo(chests.get(0), Step.OPEN_CHEST);
        Chat.info("terrafill.depot.go", Chat.hl(chests.size()), Chat.pos(chests.get(0)));
        return true;
    }

    /** Beim Stopp von aussen: Menue zu, Walker anhalten, sagen, wo eine Box steht oder liegt. */
    void abort(LocalPlayer p) {
        if (finished) return;
        walker.halt(p);
        if (p.containerMenu != p.inventoryMenu) p.closeContainer();
        if (boxPos != null && mc.level != null) {
            if (mc.level.getBlockState(boxPos).getBlock() instanceof ShulkerBoxBlock) {
                Chat.warn("terrafill.restock.left_behind", Chat.pos(boxPos));
            } else if (step == Step.RECLAIM_BOX && Restocker.countBoxes(p) < boxesBefore) {
                Chat.warn("terrafill.restock.drop_left", Chat.pos(dropTarget != null ? dropTarget : boxPos));
            }
        }
        finished = true;
    }

    /** Fuer Kampf, Essen, Heilen unterbrechen: Menue zu, danach an derselben Stelle weiter. */
    void interrupt(LocalPlayer p) {
        if (finished) return;
        mc.gameMode.stopDestroyBlock();
        walker.halt(p);
        if (p.containerMenu != p.inventoryMenu) p.closeContainer();
        switch (step) {
            case GO -> walker.clear();
            case OPEN_CHEST, TAKE, CLOSE_CHEST -> step = Step.OPEN_CHEST;
            case OPEN_BOX, PUT, CLOSE_BOX -> step = Step.OPEN_BOX;
            default -> { }
        }
        wait = 0;
    }

    void tick(LocalPlayer p, ClientLevel level) {
        if (finished) return;
        switch (step) {
            case GO -> go(p, level);
            case SCAN -> scan(p, level);
            case OPEN_CHEST -> openChest(p, level);
            case TAKE -> take(p);
            case CLOSE_CHEST -> closeChest(p);
            case PLACE_BOX -> placeBox(p, level);
            case OPEN_BOX -> openBox(p, level);
            case PUT -> put(p);
            case CLOSE_BOX -> closeBox(p);
            case BREAK_BOX -> breakBox(p, level);
            case RECLAIM_BOX -> reclaimBox(p, level);
        }
    }

    // ------------------------------- Hinlaufen -------------------------------

    private BlockPos chest() { return chests.get(chestIndex); }

    private boolean chestsLeft() { return chestIndex < chests.size(); }

    /** Zum naechsten Behaelter weiter - still, leere Kisten sind normal. */
    private void nextChest() {
        chestIndex++;
        chestEmpty = false;
        takenRound = 0;
        replans = 0;
    }

    private void goTo(BlockPos pos, Step then) {
        target = pos;
        afterGo = then;
        walker.clear();
        replans = 0;
        step = Step.GO;
        wait = 0;
    }

    private void go(LocalPlayer p, ClientLevel level) {
        if (!walker.isActive()) {
            if (withinReach(p, target)) {
                step = afterGo;
                wait = 0;
                return;
            }
            BlockPos feet = p.blockPosition();
            Walker.Node start = new Walker.Node(feet.getX(), feet.getY(), feet.getZ());
            double reach = p.blockInteractionRange() - 0.5;
            double reachSq = reach * reach;
            float eyeHeight = p.getEyeHeight();
            BlockPos t = target;
            // Der naechste begehbare Platz, von dem aus das Ziel in Reichweite ist.
            boolean ok = walker.plan(level, start, bounds, n -> {
                Vec3 eye = new Vec3(n.x() + 0.5, n.y() + eyeHeight, n.z() + 0.5);
                return new AABB(t).distanceToSqr(eye) <= reachSq ? 1 : 0;
            }, Set.of(), 0, 0, Walker.NODES_FAR);
            if (!ok) {
                unreachable(p);
                return;
            }
        }
        switch (walker.tick(p)) {
            case ARRIVED -> {
                step = afterGo;
                wait = 0;
            }
            case STUCK -> {
                if (++replans > MAX_REPLANS) unreachable(p);
                else walker.clear(); // naechster Tick plant neu
            }
            case WALKING -> { }
        }
    }

    /** Das Ziel ist nicht zu erreichen: eine Kiste wird uebersprungen, bei der Box ist Schluss. */
    private void unreachable(LocalPlayer p) {
        if (afterGo == Step.OPEN_CHEST) {
            Chat.warn("terrafill.depot.unreachable", Chat.pos(target));
            nextChest();
            if (chestsLeft()) goTo(chest(), Step.OPEN_CHEST);
            else finishUp(p);
            return;
        }
        fail(p, Chat.tr("terrafill.depot.unreachable", Chat.pos(target)));
    }

    private static boolean withinReach(LocalPlayer p, BlockPos pos) {
        double reach = p.blockInteractionRange() - 0.5;
        return new AABB(pos).distanceToSqr(p.getEyePosition()) <= reach * reach;
    }

    // -------------------------------- Kiste --------------------------------

    private void openChest(LocalPlayer p, ClientLevel level) {
        BlockPos chest = chest();
        if (p.containerMenu != p.inventoryMenu) {
            step = Step.TAKE;
            wait = 0;
            takenRound = 0;
            return;
        }
        if (++wait > TIMEOUT_TICKS) {
            Chat.warn("terrafill.depot.open_failed", Chat.pos(chest));
            nextChest();
            if (chestsLeft()) goTo(chest(), Step.OPEN_CHEST);
            else finishUp(p);
            return;
        }
        if (!level.getBlockState(chest).hasBlockEntity()) {
            nextChest();
            if (chestsLeft()) goTo(chest(), Step.OPEN_CHEST);
            else finishUp(p);
            return;
        }
        if (!withinReach(p, chest)) {
            goTo(chest, Step.OPEN_CHEST);
            return;
        }
        if (wait % 10 != 1) return;
        Direction face = Restocker.faceToward(p.getEyePosition(), chest);
        Vec3 hit = Vec3.atCenterOf(chest).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        Restocker.lookAt(p, hit);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, chest, false));
    }

    /** Stapel des Baublocks aus der Kiste holen, ein Platz bleibt frei fuer die Box. */
    private void take(LocalPlayer p) {
        AbstractContainerMenu menu = p.containerMenu;
        if (menu == p.inventoryMenu) {
            step = Step.CLOSE_CHEST;
            return;
        }
        if (++wait > TIMEOUT_TICKS * 3) {
            step = Step.CLOSE_CHEST;
            return;
        }
        int now = 0;
        while (now < MOVES_PER_TICK && Restocker.freeSlots(p) > 1) {
            int i = containerSlotWithItem(menu, p);
            if (i < 0) break;
            mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, p);
            now++;
            takenRound++;
            if (menu.getSlot(i).getItem().is(item)) break; // hat sich nicht bewegt
        }
        if (now > 0) return;

        chestEmpty = containerSlotWithItem(menu, p) < 0;
        // Kiste hat noch etwas, aber nichts passt mehr hinein: Inventar voll.
        if (!chestEmpty && takenRound == 0 && Restocker.freeSlots(p) <= 1) noRoom = true;
        step = Step.CLOSE_CHEST;
        wait = 0;
    }

    private void closeChest(LocalPlayer p) {
        if (p.containerMenu != p.inventoryMenu) p.closeContainer();
        wait = 0;
        if (noRoom) {
            finishUp(p);
            return;
        }
        if (boxPos != null) {
            // Box steht schon - weiter befuellen.
            goTo(boxPos, Step.OPEN_BOX);
            return;
        }
        if (looseStacks(p) > 0 && findFillableBox(p) >= 0) {
            step = Step.PLACE_BOX;
            return;
        }
        if (chestEmpty) {
            nextChest();
            if (chestsLeft()) goTo(chest(), Step.OPEN_CHEST);
            else finishUp(p);
            return;
        }
        // Kiste hat noch etwas, aber es gibt keine Box mehr zu fuellen: fertig.
        finishUp(p);
    }

    // --------------------------------- Box ---------------------------------

    private void placeBox(LocalPlayer p, ClientLevel level) {
        if (boxPos != null && level.getBlockState(boxPos).getBlock() instanceof ShulkerBoxBlock) {
            step = Step.OPEN_BOX;
            wait = 0;
            return;
        }
        if (++wait > TIMEOUT_TICKS) {
            fail(p, Chat.tr("terrafill.restock.place_failed"));
            return;
        }
        if (wait % 10 != 1) return;

        if (boxSlot < 0 || !Restocker.isBox(p.getInventory().getItem(boxSlot))) {
            int idx = findFillableBox(p);
            if (idx < 0) {
                finishUp(p);
                return;
            }
            boxesBefore = Restocker.countBoxes(p);
            if (idx < Inventory.SELECTION_SIZE) {
                boxSlot = idx;
            } else {
                Restocker.swapIntoHotbar(p, idx, slot);
                boxSlot = slot;
            }
            return; // naechster Versuch in 10 Ticks, dann liegt die Box in der Hotbar
        }
        BlockPos spot = Restocker.findSpot(p, level);
        if (spot == null) {
            fail(p, Chat.tr("terrafill.restock.no_space"));
            return;
        }
        BlockPos under = spot.below();
        Vec3 hit = Vec3.atCenterOf(under).add(0.0, 0.5, 0.0);
        Restocker.selectSlot(p, boxSlot);
        Restocker.lookAt(p, hit);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, under, false));
        p.swing(InteractionHand.MAIN_HAND);
        boxPos = spot.immutable();
        boxFull = false;
    }

    private void openBox(LocalPlayer p, ClientLevel level) {
        if (p.containerMenu instanceof ShulkerBoxMenu) {
            step = Step.PUT;
            wait = 0;
            return;
        }
        if (++wait > TIMEOUT_TICKS) {
            fail(p, Chat.tr("terrafill.restock.open_failed", Chat.pos(boxPos)));
            return;
        }
        if (!(level.getBlockState(boxPos).getBlock() instanceof ShulkerBoxBlock)) {
            fail(p, Chat.tr("terrafill.restock.gone"));
            return;
        }
        if (!withinReach(p, boxPos)) {
            goTo(boxPos, Step.OPEN_BOX);
            return;
        }
        if (wait % 10 != 1) return;
        Vec3 hit = Vec3.atCenterOf(boxPos).add(0.0, 0.5, 0.0);
        Restocker.lookAt(p, hit);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, boxPos, false));
    }

    /** Lose Stapel des Baublocks aus dem Inventar in die Box - der Bau-Slot bleibt draussen. */
    private void put(LocalPlayer p) {
        if (!(p.containerMenu instanceof ShulkerBoxMenu menu)) {
            step = Step.CLOSE_BOX;
            return;
        }
        if (++wait > TIMEOUT_TICKS * 3) {
            step = Step.CLOSE_BOX;
            return;
        }
        int now = 0;
        while (now < MOVES_PER_TICK && !boxFull(menu)) {
            int i = playerSlotWithItem(menu, p);
            if (i < 0) break;
            mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, p);
            now++;
            stacksPut++;
            if (menu.getSlot(i).getItem().is(item)) break;
        }
        if (now > 0) return;
        boxFull = boxFull(menu);
        step = Step.CLOSE_BOX;
        wait = 0;
    }

    private void closeBox(LocalPlayer p) {
        if (p.containerMenu instanceof ShulkerBoxMenu) p.closeContainer();
        wait = 0;
        if (boxFull || noRoom) {
            step = Step.BREAK_BOX;
            return;
        }
        // Box hat noch Platz: zurueck zur Kiste, bzw. zur naechsten, wenn diese leer war.
        if (chestEmpty) nextChest();
        if (chestsLeft()) goTo(chest(), Step.OPEN_CHEST);
        else step = Step.BREAK_BOX; // alle Kisten durch - was drin ist, mitnehmen
    }

    private void breakBox(LocalPlayer p, ClientLevel level) {
        BlockState st = level.getBlockState(boxPos);
        if (!(st.getBlock() instanceof ShulkerBoxBlock)) {
            mc.gameMode.stopDestroyBlock();
            step = Step.RECLAIM_BOX;
            wait = 0;
            dropTarget = null;
            return;
        }
        if (Restocker.freeSlots(p) == 0) {
            mc.gameMode.stopDestroyBlock();
            fail(p, Chat.tr("terrafill.restock.no_room", Chat.pos(boxPos)));
            return;
        }
        if (!withinReach(p, boxPos)) {
            goTo(boxPos, Step.BREAK_BOX);
            return;
        }
        if (++wait > TIMEOUT_TICKS * 3) {
            mc.gameMode.stopDestroyBlock();
            fail(p, Chat.tr("terrafill.restock.break_failed", Chat.pos(boxPos)));
            return;
        }
        Restocker.selectSlot(p, Restocker.bestToolSlot(p, st, slot));
        Restocker.lookAt(p, Vec3.atCenterOf(boxPos));
        if (mc.gameMode.continueDestroyBlock(boxPos, Restocker.faceToward(p.getEyePosition(), boxPos))) {
            p.swing(InteractionHand.MAIN_HAND);
        }
    }

    private void reclaimBox(LocalPlayer p, ClientLevel level) {
        if (Restocker.countBoxes(p) >= boxesBefore) {
            walker.halt(p);
            filledBoxes++;
            boxPos = null;
            boxSlot = -1;
            if (noRoom) {
                fail(p, Chat.tr("terrafill.depot.no_room"));
                return;
            }
            if (!chestsLeft() || findFillableBox(p) < 0) {
                done(p);
                return;
            }
            goTo(chest(), Step.OPEN_CHEST);
            return;
        }
        wait++;
        ItemEntity drop = Restocker.nearestBoxDrop(p, level);
        if (drop == null) {
            if (wait > TIMEOUT_TICKS) fail(p, Chat.tr("terrafill.restock.drop_lost", Chat.pos(boxPos)));
            return;
        }
        if (wait > PICKUP_TIMEOUT) {
            fail(p, Chat.tr("terrafill.restock.not_picked_up", Chat.pos(drop.blockPosition())));
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

    /** Zum Schluss: eine stehende Box mitnehmen, dann fertig. */
    private void finishUp(LocalPlayer p) {
        if (boxPos != null) {
            step = Step.BREAK_BOX;
            wait = 0;
            return;
        }
        if (noRoom) {
            fail(p, Chat.tr("terrafill.depot.no_room"));
            return;
        }
        if (filledBoxes == 0 && stacksPut == 0 && looseStacks(p) == 0) {
            fail(p, Chat.tr("terrafill.depot.empty"));
            return;
        }
        done(p);
    }

    // ------------------------------- Helfer -------------------------------

    /** Eine Shulkerbox im Inventar, die nur den Baublock enthaelt (oder leer ist) und noch Platz hat. Angefangene zuerst. */
    private int findFillableBox(LocalPlayer p) {
        int best = -1;
        int bestStacks = -1;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!Restocker.isBox(st) || !onlyBuildItem(st)) continue;
            int stacks = Restocker.stacksOfItem(st, item);
            if (stacks >= SHULKER_SLOTS) continue;
            if (stacks > bestStacks) {
                bestStacks = stacks;
                best = i;
            }
        }
        return best;
    }

    private boolean onlyBuildItem(ItemStack box) {
        ItemContainerContents c = box.get(DataComponents.CONTAINER);
        if (c == null) return true;
        return c.nonEmptyItemCopyStream().allMatch(s -> s.is(item));
    }

    private int looseStacks(LocalPlayer p) {
        int n = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (i == slot) continue;
            if (p.getInventory().getItem(i).is(item)) n++;
        }
        return n;
    }

    private int containerSlotWithItem(AbstractContainerMenu menu, LocalPlayer p) {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.getSlot(i);
            if (s.container == p.getInventory()) continue;
            if (s.getItem().is(item)) return i;
        }
        return -1;
    }

    private int playerSlotWithItem(ShulkerBoxMenu menu, LocalPlayer p) {
        for (int i = SHULKER_SLOTS; i < menu.slots.size(); i++) {
            Slot s = menu.getSlot(i);
            if (s.container != p.getInventory() || s.getContainerSlot() == slot) continue;
            if (s.getItem().is(item)) return i;
        }
        return -1;
    }

    private static boolean boxFull(ShulkerBoxMenu menu) {
        for (int i = 0; i < SHULKER_SLOTS && i < menu.slots.size(); i++) {
            if (!menu.getSlot(i).hasItem()) return false;
        }
        return true;
    }

    private void done(LocalPlayer p) {
        walker.halt(p);
        Restocker.selectSlot(p, slot);
        Chat.ok("terrafill.depot.done", Chat.hl(filledBoxes), Chat.hl(stacksPut));
        finished = true;
    }

    private void fail(LocalPlayer p, Component reason) {
        mc.gameMode.stopDestroyBlock();
        walker.halt(p);
        if (p.containerMenu != p.inventoryMenu) p.closeContainer();
        Restocker.selectSlot(p, slot);
        failure = Chat.tr("terrafill.depot.abort", reason);
        finished = true;
    }
}
