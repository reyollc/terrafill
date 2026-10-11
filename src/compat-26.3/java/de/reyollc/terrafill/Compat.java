package de.reyollc.terrafill;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.SwingAnimation;

/**
 * Versionsabhaengige Minecraft-API-Unterschiede, hier fuer MC 26.3. Welcher
 * Ordner (src/compat-&lt;mc&gt;/java) in den Client-SourceSet kommt, steuert
 * build.gradle ueber -Pmc.
 */
final class Compat {
	private Compat() {}

	/** Arm schwingen (nur Optik). In 26.3 zusaetzlich Animation und broadcast-Flag. */
	static void swing(LivingEntity entity) {
		entity.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
	}
}
