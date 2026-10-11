package de.reyollc.terrafill;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Alles, was ueber /terrafill eingestellt wird, plus Laden und Speichern nach
 * config/terrafill.json. Gespeichert wird bei jeder Aenderung, geladen beim
 * Start des Spiels. Weil der Bereich mit drinsteht, funktioniert
 * /terrafill resume auch nach einem Neustart: der Auftrag wird einfach neu
 * aufgesetzt, und was schon steht, wird beim Abtasten uebersprungen.
 *
 * Der Slot steht in der Datei 1-basiert wie im Befehl, intern 0-basiert.
 */
final class Settings {

    private static final Logger LOG = LoggerFactory.getLogger("terrafill");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Standard-Hotbar wie in DeepMine: 1 Schwert, 5 Baumaterial, 8 Essen -
    // dieselbe Hotbar taugt dann fuer beide Mods. -1 heisst "aus".
    static final double REACH_MIN = 4.0;
    static final double REACH_MAX = 5.4;
    int slot = 4;
    int swordSlot = 0;
    int foodSlot = 7;
    int speed = 8;
    /**
     * Reichweite fuers Setzen, vom Auge zur Klickflaeche. Vanilla-Attribut ist
     * 4.5; der Server nimmt einen Block mehr an (Latenzreserve), also geht bis
     * 5.4 - mehr Zellen je Standort, weniger Laufen. Standard bleibt 4.5.
     */
    double reach = 4.5;
    /** Wasser nur mit einem Deckel versehen (oberste Zelle), statt es bis zum Grund zuzuschuetten. */
    boolean waterLid = true;
    // Die Ecken legen nur den Grundriss fest; ihre Hoehe spielt keine Rolle.
    BlockPos pos1;
    BlockPos pos2;
    Integer yTop;       // null = Oberkante aus dem Gelaende am Rand, beim Start ermittelt
    Integer yBottom;    // null = Oberkante minus AUTO_DEPTH; die Flutung findet den Boden von selbst
    String dimension;   // Dimension, in der die Ecken gesetzt wurden
    /** Lagerbereich (zwei Ecken, mit Hoehe), in dem die Kisten fuers Nachfuellen stehen. null = kein Depot. */
    BlockPos depotPos1;
    BlockPos depotPos2;

    static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("terrafill.json");
    }

    /** Der Grundriss aus den beiden Ecken (Hoehe 0, wird beim Start ersetzt). null, solange eine Ecke fehlt. */
    Region footprint() {
        if (pos1 == null || pos2 == null) return null;
        return Region.of(pos1, pos2).withY(0, 0);
    }

    void applyTo(FillTask t) {
        t.setSlot(slot);
        t.setSwordSlot(swordSlot);
        t.setFoodSlot(foodSlot);
        t.setSpeed(speed);
        t.setReach(reach);
        t.setWaterLid(waterLid);
        t.setDepot(depot());
    }

    /** Mehrere Zeilen fuer /terrafill config, in der Spielsprache. */
    List<Component> describe() {
        List<Component> out = new ArrayList<>();
        out.add(Chat.tr("terrafill.config.line", Chat.hl(slot + 1), slotText(swordSlot), slotText(foodSlot),
                Chat.hl(speed), Chat.hl(String.format(Locale.ROOT, "%.1f", reach)),
                Chat.tr(waterLid ? "terrafill.water.lid_short" : "terrafill.water.fill_short")));
        Region r = footprint();
        out.add(r == null ? Chat.tr("terrafill.config.no_region") : r.describeFootprint());
        out.add(heightLine());
        out.add(depotLine());
        out.add(Chat.tr("terrafill.config.file", Chat.hl(file())));
        return out;
    }

    /** "Hoehe: automatisch ..." / "Oberkante 64, Tiefe automatisch" / "62 bis 70". */
    MutableComponent heightLine() {
        if (yTop == null) return Chat.tr("terrafill.height.auto");
        if (yBottom == null) return Chat.tr("terrafill.height.top", Chat.hl(yTop));
        return Chat.tr("terrafill.height.fixed", Chat.hl(yBottom), Chat.hl(yTop));
    }

    /** Der Lagerbereich aus den beiden Depot-Ecken, null solange eine fehlt. */
    Region depot() {
        if (depotPos1 == null || depotPos2 == null) return null;
        return Region.of(depotPos1, depotPos2);
    }

    /** "Depot: x y z bis x y z" oder "Kein Depot". */
    MutableComponent depotLine() {
        Region d = depot();
        if (d == null) return Chat.tr("terrafill.depot.none");
        return Chat.tr("terrafill.depot.line", Chat.pos(d.min()), Chat.pos(d.max()));
    }

    private static MutableComponent slotText(int slot) {
        return slot < 0 ? Chat.tr("terrafill.off") : Chat.hl(slot + 1);
    }

    // ------------------------------ speichern ------------------------------

    /** Slots stehen in der Datei 1-basiert wie im Befehl, 0 heisst "aus". */
    void save() {
        JsonObject o = new JsonObject();
        o.addProperty("slot", slot + 1);
        o.addProperty("swordSlot", swordSlot < 0 ? 0 : swordSlot + 1);
        o.addProperty("foodSlot", foodSlot < 0 ? 0 : foodSlot + 1);
        o.addProperty("speed", speed);
        o.addProperty("reach", reach);
        o.addProperty("waterLid", waterLid);
        if (pos1 != null) o.add("pos1", pos(pos1));
        if (pos2 != null) o.add("pos2", pos(pos2));
        if (yTop != null) o.addProperty("yTop", yTop);
        if (yBottom != null) o.addProperty("yBottom", yBottom);
        if (dimension != null) o.addProperty("dimension", dimension);
        if (depotPos1 != null) o.add("depotPos1", pos(depotPos1));
        if (depotPos2 != null) o.add("depotPos2", pos(depotPos2));
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOG.warn("TerraFill: konnte {} nicht schreiben: {}", file(), ex.toString());
        }
    }

    private static JsonArray pos(BlockPos p) {
        JsonArray a = new JsonArray();
        a.add(p.getX());
        a.add(p.getY());
        a.add(p.getZ());
        return a;
    }

    // -------------------------------- laden --------------------------------

    static Settings load() {
        Settings s = new Settings();
        Path f = file();
        if (!Files.exists(f)) return s;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            s.slot = slot(o, "slot", s.slot, false);
            s.swordSlot = slot(o, "swordSlot", s.swordSlot, true);
            s.foodSlot = slot(o, "foodSlot", s.foodSlot, true);
            if (o.has("speed")) s.speed = Math.max(1, Math.min(64, o.get("speed").getAsInt()));
            if (o.has("reach")) s.reach = Math.max(REACH_MIN, Math.min(REACH_MAX, o.get("reach").getAsDouble()));
            if (o.has("waterLid")) s.waterLid = o.get("waterLid").getAsBoolean();
            if (o.has("pos1")) s.pos1 = pos(o.getAsJsonArray("pos1"));
            if (o.has("pos2")) s.pos2 = pos(o.getAsJsonArray("pos2"));
            if (o.has("yTop")) s.yTop = o.get("yTop").getAsInt();
            if (o.has("yBottom") && s.yTop != null) s.yBottom = Math.min(o.get("yBottom").getAsInt(), s.yTop);
            if (o.has("dimension")) s.dimension = o.get("dimension").getAsString();
            if (o.has("depotPos1")) s.depotPos1 = pos(o.getAsJsonArray("depotPos1"));
            if (o.has("depotPos2")) s.depotPos2 = pos(o.getAsJsonArray("depotPos2"));
            LOG.info("TerraFill: Einstellungen aus {} geladen", f);
        } catch (Exception ex) {
            // Eine kaputte Datei darf den Mod nicht lahmlegen. Standardwerte,
            // und beim naechsten Speichern wird sie sauber ueberschrieben.
            LOG.warn("TerraFill: konnte {} nicht lesen, benutze Standardwerte: {}", f, ex.toString());
            return new Settings();
        }
        return s;
    }

    private static BlockPos pos(JsonArray a) {
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    private static int slot(JsonObject o, String key, int fallback, boolean optional) {
        if (!o.has(key)) return fallback;
        int v = o.get(key).getAsInt();
        if (v <= 0) return optional ? -1 : fallback;
        return Math.min(v, 9) - 1;
    }
}
