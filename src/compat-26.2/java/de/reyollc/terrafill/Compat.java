package de.reyollc.terrafill;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;

/**
 * Versionsabhaengige Minecraft-API-Unterschiede, hier fuer MC 26.2. Welcher
 * Ordner (src/compat-&lt;mc&gt;/java) in den Client-SourceSet kommt, steuert
 * build.gradle ueber -Pmc.
 */
final class Compat {
	private Compat() {}

	/** Arm schwingen (nur Optik). In 26.2 nimmt swing nur die Hand. */
	static void swing(LivingEntity entity) {
		entity.swing(InteractionHand.MAIN_HAND);
	}
}
