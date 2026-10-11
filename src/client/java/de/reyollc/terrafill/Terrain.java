package de.reyollc.terrafill;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Liest aus dem Gelaende ab, wie hoch das fertige Gelaende werden soll: die
 * Bodenhoehe entlang des Randes des Grundrisses, davon der Median. Der Rand
 * liegt bei einem vernuenftig gesetzten Bereich auf dem Gelaende, das man
 * erhalten will; Median statt Mittelwert, damit ein paar Randspalten im Fluss
 * oder unter einem Baum das Ergebnis nicht verziehen.
 */
final class Terrain {

    private Terrain() {}

    /**
     * Oberkante fuer den Grundriss: Median der Bodenhoehe aller Randspalten.
     * fallback, falls keine Randspalte geladen ist.
     */
    static int surfaceLevel(ClientLevel level, Region footprint, int fallback) {
        List<Integer> heights = new ArrayList<>();
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            heights.add(groundLevel(level, x, footprint.minZ()));
            if (footprint.maxZ() != footprint.minZ()) heights.add(groundLevel(level, x, footprint.maxZ()));
        }
        for (int z = footprint.minZ() + 1; z < footprint.maxZ(); z++) {
            heights.add(groundLevel(level, footprint.minX(), z));
            if (footprint.maxX() != footprint.minX()) heights.add(groundLevel(level, footprint.maxX(), z));
        }
        heights.removeIf(h -> h == null);
        if (heights.isEmpty()) return fallback;
        Collections.sort(heights);
        return heights.get(heights.size() / 2);
    }

    /**
     * Y des Bodenblocks einer Spalte: vom hoechsten Block mit Kollision oder
     * Fluessigkeit (Heightmap) abwaerts, bis etwas Festes kommt, das weder
     * Baum noch Wasser ist. null, wenn der Chunk nicht geladen ist.
     */
    static Integer groundLevel(ClientLevel level, int x, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, 0, z);
        if (!level.isLoaded(m)) return null;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        while (y > level.getMinY()) {
            m.set(x, y, z);
            if (isGround(level, level.getBlockState(m), m)) break;
            y--;
        }
        return y;
    }

    private static boolean isGround(ClientLevel level, BlockState st, BlockPos pos) {
        if (st.getCollisionShape(level, pos).isEmpty()) return false;
        if (!st.getFluidState().isEmpty()) return false;
        return !st.is(BlockTags.LEAVES) && !st.is(BlockTags.LOGS);
    }
}
