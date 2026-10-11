package de.reyollc.terrafill;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Alle Chat-Ausgaben des Mods. Texte kommen aus assets/terrafill/lang und werden
 * vom Spiel in der eingestellten Sprache aufgeloest; hier gibt es nur Schluessel.
 *
 * Farben nach Art der Nachricht:
 *   info  weiss    Einstellungen, Status, Hilfe
 *   ok    gruen    etwas ist gut ausgegangen (Ebene fertig, Nachschub geholt)
 *   warn  gold     Aufmerksamkeit, aber kein Stopp
 *   fail  rot      Abbruch, der Mod steht
 * Zahlen und Positionen werden innerhalb der Nachricht gelb hervorgehoben.
 */
final class Chat {

    private Chat() {}

    static MutableComponent tr(String key, Object... args) {
        return Component.translatable(key, args);
    }

    /** Hervorhebung fuer Zahlen, Slots, Namen innerhalb einer Nachricht. */
    static MutableComponent hl(Object value) {
        return Component.literal(String.valueOf(value)).withStyle(ChatFormatting.YELLOW);
    }

    static MutableComponent pos(BlockPos p) {
        return hl(p.toShortString());
    }

    static void info(String key, Object... args) { send(tr(key, args).withStyle(ChatFormatting.WHITE)); }
    static void ok(String key, Object... args)   { send(tr(key, args).withStyle(ChatFormatting.GREEN)); }
    static void warn(String key, Object... args) { send(tr(key, args).withStyle(ChatFormatting.GOLD)); }
    static void fail(String key, Object... args) { send(tr(key, args).withStyle(ChatFormatting.RED)); }

    /** Fertig gebaute Nachricht in einer Farbe senden, z.B. zusammengesetzte Abbruchgruende. */
    static void send(ChatFormatting color, Component body) {
        send(body.copy().withStyle(color));
    }

    /** Eine Hilfezeile: Befehl in Cyan, Beschreibung in Grau. */
    static void helpLine(String cmdKey, String descKey) {
        send(Component.empty()
                .append(Component.literal("/terrafill ").withStyle(ChatFormatting.AQUA))
                .append(tr(cmdKey).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("  ").withStyle(ChatFormatting.GRAY))
                .append(tr(descKey).withStyle(ChatFormatting.GRAY)));
    }

    private static void send(Component body) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        // Leerer Wurzelknoten, damit Praefix und Text ihre Farbe unabhaengig behalten.
        p.sendSystemMessage(Component.empty()
                .append(tr("terrafill.prefix").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                .append(body));
    }
}
