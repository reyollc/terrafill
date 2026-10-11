package de.reyollc.terrafill;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.MutableComponent;

/** Achsenparalleler Quader aus zwei Ecken. Alle Grenzen einschliesslich. */
record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    static Region of(BlockPos a, BlockPos b) {
        return new Region(
                Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
    }

    /** Derselbe Grundriss mit anderem Hoehenbereich. */
    Region withY(int y1, int y2) {
        return new Region(minX, Math.min(y1, y2), minZ, maxX, Math.max(y1, y2), maxZ);
    }

    /** Grundriss um margin nach aussen, Hoehe um vertical nach oben und unten erweitert. */
    Region grow(int margin, int vertical) {
        return new Region(minX - margin, minY - vertical, minZ - margin, maxX + margin, maxY + vertical, maxZ + margin);
    }

    /** So weit vergroessert, dass pos mit etwas Rand drinliegt. */
    Region including(BlockPos pos, int margin) {
        return new Region(
                Math.min(minX, pos.getX() - margin), Math.min(minY, pos.getY() - margin), Math.min(minZ, pos.getZ() - margin),
                Math.max(maxX, pos.getX() + margin), Math.max(maxY, pos.getY() + margin), Math.max(maxZ, pos.getZ() + margin));
    }

    boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    boolean containsXZ(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    int sizeX() { return maxX - minX + 1; }
    int sizeY() { return maxY - minY + 1; }
    int sizeZ() { return maxZ - minZ + 1; }

    long volume() { return (long) sizeX() * sizeY() * sizeZ(); }

    BlockPos min() { return new BlockPos(minX, minY, minZ); }
    BlockPos max() { return new BlockPos(maxX, maxY, maxZ); }

    long area() { return (long) sizeX() * sizeZ(); }

    /** Nur der Grundriss: "x z bis x z, 10x20 Spalten". */
    MutableComponent describeFootprint() {
        return Chat.tr("terrafill.pos.footprint", Chat.hl(minX + " " + minZ), Chat.hl(maxX + " " + maxZ),
                Chat.hl(sizeX()), Chat.hl(sizeZ()), Chat.hl(area()));
    }
}
