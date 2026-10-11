package de.reyollc.terrafill;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Locale;

public final class TerraFill implements ClientModInitializer {

    /** Aus der fabric.mod.json, damit /terrafill status zeigt, welche Jar geladen ist. */
    private static final String VERSION = FabricLoader.getInstance().getModContainer("terrafill")
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("?");

    /** Obergrenze fuer den Grundriss, 1024 x 1024 Spalten; gearbeitet wird ohnehin kachelweise. */
    private static final long MAX_AREA = 1_048_576L;
    /** Ohne feste Tiefe: so weit unter die Oberkante reicht die Flutung hoechstens. */
    private static final int AUTO_DEPTH = 48;
    private static final int HELP_LINES = 13;

    private static FillTask task;
    private static Settings cfg = new Settings();

    @Override
    public void onInitializeClient() {
        cfg = Settings.load();

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (task != null && task.isRunning()) task.tick();
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommands.literal("terrafill")
                .executes(ctx -> help())
                .then(ClientCommands.literal("help").executes(ctx -> help()))
                .then(corner("pos1", 1))
                .then(corner("pos2", 2))
                .then(ClientCommands.literal("y")
                    .then(ClientCommands.literal("auto").executes(ctx -> {
                        cfg.yTop = null;
                        cfg.yBottom = null;
                        cfg.save();
                        Chat.ok("terrafill.height.auto");
                        return 1;
                    }))
                    .then(ClientCommands.argument("top", IntegerArgumentType.integer(-2048, 2047))
                        .executes(ctx -> {
                            cfg.yTop = IntegerArgumentType.getInteger(ctx, "top");
                            cfg.yBottom = null;
                            cfg.save();
                            Chat.ok("terrafill.height.top", Chat.hl(cfg.yTop));
                            return 1;
                        })
                        .then(ClientCommands.argument("bottom", IntegerArgumentType.integer(-2048, 2047))
                            .executes(ctx -> {
                                int a = IntegerArgumentType.getInteger(ctx, "top");
                                int b = IntegerArgumentType.getInteger(ctx, "bottom");
                                cfg.yTop = Math.max(a, b);
                                cfg.yBottom = Math.min(a, b);
                                cfg.save();
                                Chat.ok("terrafill.height.fixed", Chat.hl(cfg.yBottom), Chat.hl(cfg.yTop));
                                return 1;
                            }))))
                .then(ClientCommands.literal("start").executes(ctx -> start()))
                .then(ClientCommands.literal("stop").executes(ctx -> {
                    if (task != null && task.isRunning()) task.stop(Chat.tr("terrafill.stopped"));
                    else Chat.info("terrafill.not_running");
                    return 1;
                }))
                .then(ClientCommands.literal("resume").executes(ctx -> resume()))
                .then(ClientCommands.literal("status").executes(ctx -> {
                    if (task == null) Chat.info("terrafill.status.none", Chat.hl(VERSION));
                    else Chat.info("terrafill.status.task", Chat.hl(VERSION), task.statusLine());
                    return 1;
                }))
                .then(ClientCommands.literal("slot")
                    .then(ClientCommands.argument("slot", IntegerArgumentType.integer(1, 9))
                        .executes(ctx -> {
                            cfg.slot = IntegerArgumentType.getInteger(ctx, "slot") - 1;
                            changed();
                            Chat.ok("terrafill.slot.set", Chat.hl(cfg.slot + 1));
                            return 1;
                        })))
                .then(ClientCommands.literal("sword")
                    .then(ClientCommands.literal("off").executes(ctx -> {
                        cfg.swordSlot = -1;
                        changed();
                        Chat.warn("terrafill.sword.off");
                        return 1;
                    }))
                    .then(ClientCommands.argument("slot", IntegerArgumentType.integer(1, 9))
                        .executes(ctx -> {
                            cfg.swordSlot = IntegerArgumentType.getInteger(ctx, "slot") - 1;
                            changed();
                            Chat.ok("terrafill.sword.set", Chat.hl(cfg.swordSlot + 1));
                            return 1;
                        })))
                .then(ClientCommands.literal("food")
                    .then(ClientCommands.literal("off").executes(ctx -> {
                        cfg.foodSlot = -1;
                        changed();
                        Chat.warn("terrafill.food.off");
                        return 1;
                    }))
                    .then(ClientCommands.argument("slot", IntegerArgumentType.integer(1, 9))
                        .executes(ctx -> {
                            cfg.foodSlot = IntegerArgumentType.getInteger(ctx, "slot") - 1;
                            changed();
                            Chat.ok("terrafill.food.set", Chat.hl(cfg.foodSlot + 1));
                            return 1;
                        })))
                .then(ClientCommands.literal("depot")
                    .then(depotCorner("pos1", 1))
                    .then(depotCorner("pos2", 2))
                    .then(ClientCommands.literal("list").executes(ctx -> {
                        showDepot(true);
                        return 1;
                    }))
                    .then(ClientCommands.literal("clear").executes(ctx -> {
                        cfg.depotPos1 = null;
                        cfg.depotPos2 = null;
                        changed();
                        Chat.ok("terrafill.depot.cleared");
                        return 1;
                    })))
                .then(ClientCommands.literal("water")
                    .then(ClientCommands.literal("lid").executes(ctx -> {
                        cfg.waterLid = true;
                        changed();
                        Chat.ok("terrafill.water.lid");
                        return 1;
                    }))
                    .then(ClientCommands.literal("fill").executes(ctx -> {
                        cfg.waterLid = false;
                        changed();
                        Chat.ok("terrafill.water.fill");
                        return 1;
                    })))
                .then(ClientCommands.literal("speed")
                    .then(ClientCommands.argument("speed", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> {
                            cfg.speed = IntegerArgumentType.getInteger(ctx, "speed");
                            changed();
                            Chat.ok("terrafill.speed.set", Chat.hl(cfg.speed));
                            return 1;
                        })))
                .then(ClientCommands.literal("reach")
                    .then(ClientCommands.argument("reach", DoubleArgumentType.doubleArg(Settings.REACH_MIN, Settings.REACH_MAX))
                        .executes(ctx -> {
                            cfg.reach = DoubleArgumentType.getDouble(ctx, "reach");
                            changed();
                            Chat.ok("terrafill.reach.set", Chat.hl(String.format(Locale.ROOT, "%.1f", cfg.reach)));
                            return 1;
                        })))
                .then(ClientCommands.literal("config")
                    .executes(ctx -> {
                        Chat.info("terrafill.config.header", Chat.hl(VERSION));
                        for (Component line : cfg.describe()) Chat.send(ChatFormatting.WHITE, line);
                        return 1;
                    })
                    .then(ClientCommands.literal("reset").executes(ctx -> {
                        cfg = new Settings();
                        changed();
                        Chat.ok("terrafill.config.reset");
                        return 1;
                    })))));
    }

    /** pos1 / pos2, wahlweise mit x z oder als das Feld, auf dem man steht. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> corner(String name, int which) {
        return ClientCommands.literal(name)
            .executes(ctx -> setCorner(which, null))
            .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                    .executes(ctx -> setCorner(which, new BlockPos(
                            IntegerArgumentType.getInteger(ctx, "x"), 0,
                            IntegerArgumentType.getInteger(ctx, "z"))))));
    }

    private static int setCorner(int which, BlockPos given) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) return 1;

        // Ohne Koordinaten: das Feld, auf dem man steht. Nur X und Z zaehlen,
        // die Hoehe holt sich der Start aus dem Gelaende.
        BlockPos pos = given != null ? given : p.blockPosition();
        String dim = dimensionOf(level);
        if (cfg.dimension != null && !cfg.dimension.equals(dim)) {
            cfg.pos1 = null;
            cfg.pos2 = null;
            Chat.warn("terrafill.pos.dimension_reset");
        }
        cfg.dimension = dim;
        if (which == 1) cfg.pos1 = pos.immutable();
        else cfg.pos2 = pos.immutable();
        cfg.save();
        Chat.ok("terrafill.pos.set", Chat.hl(which), Chat.hl(pos.getX() + " " + pos.getZ()));
        showRegion();
        return 1;
    }

    /** depot pos1 / pos2, mit x y z oder als der Block, den man anschaut (sonst das eigene Feld). */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> depotCorner(String name, int which) {
        return ClientCommands.literal(name)
            .executes(ctx -> setDepotCorner(which, null))
            .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                .then(ClientCommands.argument("y", IntegerArgumentType.integer(-2048, 2047))
                    .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                        .executes(ctx -> setDepotCorner(which, new BlockPos(
                                IntegerArgumentType.getInteger(ctx, "x"),
                                IntegerArgumentType.getInteger(ctx, "y"),
                                IntegerArgumentType.getInteger(ctx, "z")))))));
    }

    /**
     * Eine Ecke des Lagerbereichs merken. Ohne Koordinaten: der Block, den man
     * gerade anschaut - bei einer Kistenwand also die Eckkiste -, sonst das
     * Feld, auf dem man steht. Mit Hoehe, weil Kisten gestapelt sind.
     */
    private static int setDepotCorner(int which, BlockPos given) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) return 1;

        BlockPos pos = given;
        if (pos == null) {
            pos = mc.hitResult instanceof BlockHitResult hit ? hit.getBlockPos() : p.blockPosition();
        }
        if (which == 1) cfg.depotPos1 = pos.immutable();
        else cfg.depotPos2 = pos.immutable();
        changed();
        Chat.ok("terrafill.depot.corner", Chat.hl(which), Chat.pos(pos));
        showDepot(false);
        return 1;
    }

    /** Lagerbereich und, soweit geladen, die Zahl der Behaelter darin. */
    private static void showDepot(boolean always) {
        Minecraft mc = Minecraft.getInstance();
        Region d = cfg.depot();
        if (d == null) {
            if (always) Chat.info("terrafill.depot.none");
            return;
        }
        if (d.volume() > Depot.MAX_DEPOT_VOLUME) {
            Chat.fail("terrafill.depot.too_big", Chat.hl(d.volume()), Chat.hl(Depot.MAX_DEPOT_VOLUME));
            return;
        }
        int n = mc.level == null || mc.player == null ? 0 : Depot.findContainers(mc.level, d, mc.player.blockPosition()).size();
        Chat.info("terrafill.depot.line", Chat.pos(d.min()), Chat.pos(d.max()));
        Chat.info("terrafill.depot.count", Chat.hl(n));
    }
    private static void showRegion() {
        Region r = cfg.footprint();
        if (r == null) return;
        Chat.send(ChatFormatting.WHITE, r.describeFootprint());
        Chat.send(ChatFormatting.WHITE, cfg.heightLine());
    }

    /** Nach jeder Aenderung: auf Platte schreiben und einem laufenden Auftrag mitgeben. */
    private static void changed() {
        cfg.save();
        if (task != null) cfg.applyTo(task);
    }

    private static int help() {
        Chat.info("terrafill.help.header", Chat.hl(VERSION));
        for (int i = 1; i <= HELP_LINES; i++) {
            Chat.helpLine("terrafill.help." + i + ".cmd", "terrafill.help." + i + ".desc");
        }
        Chat.info("terrafill.help.footer");
        return 1;
    }

    private static int start() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) return 1;
        if (task != null && task.isRunning()) {
            Chat.info("terrafill.start.running", task.statusLine());
            return 1;
        }

        Region footprint = cfg.footprint();
        if (footprint == null) {
            Chat.fail("terrafill.start.no_region");
            return 1;
        }
        String dim = dimensionOf(level);
        if (cfg.dimension != null && !cfg.dimension.equals(dim)) {
            Chat.fail("terrafill.start.wrong_dimension", Chat.hl(cfg.dimension), Chat.hl(dim));
            return 1;
        }
        if (footprint.area() > MAX_AREA) {
            Chat.fail("terrafill.start.too_big", Chat.hl(footprint.area()), Chat.hl(MAX_AREA));
            return 1;
        }
        ItemStack st = p.getInventory().getItem(cfg.slot);
        if (!(st.getItem() instanceof BlockItem bi)) {
            Chat.fail("terrafill.start.no_block", Chat.hl(cfg.slot + 1));
            return 1;
        }

        // Hoehe: Oberkante aus dem Gelaende am Rand (oder fest eingestellt),
        // Tiefe grosszuegig - was wirklich gefuellt wird, entscheidet die Flutung.
        int top = cfg.yTop != null ? cfg.yTop : Terrain.surfaceLevel(level, footprint, p.blockPosition().getY() - 1);
        int bottom = cfg.yBottom != null ? cfg.yBottom : top - AUTO_DEPTH;
        bottom = Math.max(level.getMinY(), Math.min(bottom, top));
        top = Math.min(level.getMaxY(), top);
        Region r = footprint.withY(bottom, top);
        Chat.info(cfg.yTop == null ? "terrafill.start.height_auto" : "terrafill.start.height_fixed",
                Chat.hl(top), Chat.hl(bottom));

        FillTask t = new FillTask(r, dim, bi.getBlock(), st.getItem());
        cfg.applyTo(t);
        task = t;
        t.start(level, p.blockPosition());
        Chat.ok("terrafill.start.ok", t.blockName(), Chat.hl(footprint.sizeX()), Chat.hl(footprint.sizeZ()), Chat.hl(t.tileCount()));
        return 1;
    }

    /**
     * Weitermachen nach Stopp oder Neustart. Ohne Auftrag im Speicher wird er
     * aus den gespeicherten Ecken neu aufgesetzt; was schon steht, ueberspringt
     * die Abtastung von selbst.
     */
    private static int resume() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return 1;
        if (task == null) {
            if (cfg.footprint() == null) {
                Chat.fail("terrafill.resume.nothing");
                return 1;
            }
            return start();
        }
        if (task.isRunning()) {
            Chat.info("terrafill.start.running", task.statusLine());
            return 1;
        }
        if (task.isDone()) {
            task = null;
            return start();
        }
        if (task.resume(mc.level)) Chat.ok("terrafill.resume.ok", task.statusLine());
        return 1;
    }

    private static String dimensionOf(ClientLevel level) {
        return level.dimension().identifier().toString();
    }

}
