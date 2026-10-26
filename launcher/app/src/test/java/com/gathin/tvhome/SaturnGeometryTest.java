package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

/** Orbit batching must preserve the 17 independent paths, not join moons or change their coordinates. */
public class SaturnGeometryTest {
    @Test public void actualPlanetAndMoonMeshesHaveClockwiseOutwardFacesAndMatchingNormals() {
        for (int[] resolution : new int[][]{{96, 56}, {20, 12}}) {
            float[] mesh = SaturnGeometry.sphereVertices(resolution[0], resolution[1]);
            assertEquals(resolution[0] * resolution[1] * 36, mesh.length);
            int visibleTriangles = 0;
            for (int offset = 0; offset < mesh.length; offset += 18) {
                for (int vertex = 0; vertex < 3; vertex++) {
                    int at = offset + vertex * 6;
                    double norm = 0;
                    for (int axis = 0; axis < 3; axis++) {
                        assertEquals(mesh[at + axis], mesh[at + 3 + axis], 0);
                        norm += mesh[at + axis] * (double) mesh[at + axis];
                    }
                    assertEquals(1, norm, 0.0000002);
                }
                double[] ab = new double[3], ac = new double[3], center = new double[3];
                for (int axis = 0; axis < 3; axis++) {
                    ab[axis] = mesh[offset + 6 + axis] - (double) mesh[offset + axis];
                    ac[axis] = mesh[offset + 12 + axis] - (double) mesh[offset + axis];
                    center[axis] = (mesh[offset + axis] + (double) mesh[offset + 6 + axis] + mesh[offset + 12 + axis]) / 3;
                }
                double[] cross = {ab[1] * ac[2] - ab[2] * ac[1], ab[2] * ac[0] - ab[0] * ac[2], ab[0] * ac[1] - ab[1] * ac[0]};
                double areaSquared = 0, outward = 0;
                for (int axis = 0; axis < 3; axis++) { areaSquared += cross[axis] * cross[axis]; outward += cross[axis] * center[axis]; }
                if (areaSquared < 1e-24) continue; // Coincident pole vertices have no visible triangle.
                assertTrue("Every production sphere face is outward-CW", outward < 0);
                visibleTriangles++;
            }
            assertEquals(resolution[0] * (resolution[1] - 1) * 2, visibleTriangles);
        }
    }

    @Test public void explorationNearOrInsideASphereKeepsItsBackFaces() {
        assertTrue(SaturnGeometry.sphereCanCull(3.1f, 1));
        assertFalse(SaturnGeometry.sphereCanCull(1, 1));
        assertFalse(SaturnGeometry.sphereCanCull(1.09f, 1));
        assertTrue(SaturnGeometry.sphereCanCull(1.11f, 1));
        assertFalse(SaturnGeometry.sphereCanCull(.15f, .127f));
        assertFalse(SaturnGeometry.sphereCanCull(-2, .127f));
        assertTrue(SaturnGeometry.sphereCanCull(.24f, .127f));
    }

    @Test public void batchedSegmentsMatchEveryOriginalStripEndpoint() {
        int segments = 160;
        float[] lines = SaturnGeometry.orbitLines(segments);
        assertEquals(17 * segments * 2 * 6, lines.length);
        for (int moon = 0; moon < 17; moon++) for (int segment = 0; segment < segments; segment++) {
            int offset = (moon * segments + segment) * 12;
            double period = SaturnOrbits.ELEMENTS[moon][6];
            double[] start = SaturnOrbits.position(moon, segment / (double) segments * period, true);
            double[] end = SaturnOrbits.position(moon, (segment + 1) / (double) segments * period, true);
            for (int axis = 0; axis < 3; axis++) {
                assertEquals((float) start[axis], lines[offset + axis], 0);
                assertEquals((float) end[axis], lines[offset + 6 + axis], 0);
                assertEquals(0, lines[offset + 3 + axis], 0);
                assertEquals(0, lines[offset + 9 + axis], 0);
            }
        }
    }

    @Test public void eachMoonClosesIndependentlyAndConsecutiveSegmentsJoin() {
        for (int segments : new int[]{8, 64, 160}) {
            float[] lines = SaturnGeometry.orbitLines(segments);
            for (int moon = 0; moon < 17; moon++) {
                int start = moon * segments * 12;
                for (int axis = 0; axis < 3; axis++) assertEquals(lines[start + axis], lines[start + (segments - 1) * 12 + 6 + axis], .00001f);
                for (int segment = 1; segment < segments; segment++) for (int axis = 0; axis < 3; axis++)
                    assertEquals(lines[start + (segment - 1) * 12 + 6 + axis], lines[start + segment * 12 + axis], 0);
            }
        }
    }

    @Test public void invalidResolutionIsRejectedBeforeAllocating() {
        for (int segments : new int[]{-1, 0, 2, 4097, Integer.MAX_VALUE}) {
            try { SaturnGeometry.orbitLines(segments); fail("Invalid resolution accepted"); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
