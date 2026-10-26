package com.gathin.tvhome;

/** Static scene geometry that can be checked without creating an Android or GL context. */
final class SaturnGeometry {
    private SaturnGeometry() { }

    /** Existing position/normal triangles, with clockwise outward faces. */
    static float[] sphereVertices(int slices, int stacks) {
        if (slices < 3 || slices > 256 || stacks < 2 || stacks > 128)
            throw new IllegalArgumentException("Invalid sphere resolution");
        float[] data = new float[slices * stacks * 6 * 6];
        int offset = 0;
        int[] dx = {0, 1, 0, 1, 1, 0}, dy = {0, 0, 1, 0, 1, 1};
        for (int y = 0; y < stacks; y++) for (int x = 0; x < slices; x++) for (int v = 0; v < 6; v++) {
            double lat = -Math.PI / 2 + Math.PI * (y + dy[v]) / stacks;
            double lon = 2 * Math.PI * (x + dx[v]) / slices;
            float px = (float) (Math.cos(lat) * Math.cos(lon));
            float py = (float) Math.sin(lat), pz = (float) (Math.cos(lat) * Math.sin(lon));
            data[offset++] = px; data[offset++] = py; data[offset++] = pz;
            data[offset++] = px; data[offset++] = py; data[offset++] = pz;
        }
        return data;
    }

    /** Preserve visible interior/back faces when exploration intersects a sphere's near plane. */
    static boolean sphereCanCull(float centerDepth, float radius) {
        return centerDepth - radius > .1f;
    }

    /** Same per-moon segments as the old separate strips, packed into one GL_LINES draw. */
    static float[] orbitLines(int segments) {
        if (segments < 3 || segments > 4096) throw new IllegalArgumentException("Invalid orbit segment count");
        float[] lines = new float[SaturnOrbits.ELEMENTS.length * segments * 2 * 6];
        int offset = 0;
        for (int moon = 0; moon < SaturnOrbits.ELEMENTS.length; moon++) {
            double[] previous = SaturnOrbits.position(moon, 0, true);
            for (int segment = 1; segment <= segments; segment++) {
                double day = segment / (double) segments * SaturnOrbits.ELEMENTS[moon][6];
                double[] next = SaturnOrbits.position(moon, day, true);
                for (int axis = 0; axis < 3; axis++) lines[offset + axis] = (float) previous[axis];
                offset += 6;
                for (int axis = 0; axis < 3; axis++) lines[offset + axis] = (float) next[axis];
                offset += 6;
                previous = next;
            }
        }
        return lines;
    }
}
