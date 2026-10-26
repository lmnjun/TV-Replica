package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the saved tile order: file format, dock_apps reset and the sort step. */
public class OrderTest {

    private static final List<String> DOCK = Arrays.asList("com.plexapp.android", "com.netflix.ninja");

    @Test
    public void roundTrips() {
        Order o = new Order(Order.dockKey(DOCK), Arrays.asList("b/.B", "a/.A", "c/.C"));
        Order back = Order.decode(Order.encode(o));
        assertEquals(o.dock, back.dock);
        assertEquals(o.keys, back.keys);
    }

    @Test
    public void emptyOrderRoundTrips() {
        Order back = Order.decode(Order.encode(new Order("", Collections.emptyList())));
        assertEquals("", back.dock);
        assertTrue(back.keys.isEmpty());
    }

    @Test
    public void rejectsWhatIsNotAnOrder() {
        assertNull(Order.decode(null));
        assertNull(Order.decode(""));
        assertNull(Order.decode("{\"version\":2}"));
        assertNull(Order.decode("tvhome-order 2\ndock x\na/.A\n"));
        assertNull(Order.decode("tvhome-order 1\na/.A\n"));                 // no dock line
        assertNull(Order.decode("tvhome-order 1\ndock x\na/.A\n\nb/.B\n"));  // blank key in the middle
        assertNull(Order.decode("tvhome-order 1\ndock x\na/.A\na/.A\n"));    // duplicate key
    }

    @Test
    public void ranksFollowTheSavedOrder() {
        Order o = new Order(Order.dockKey(DOCK), Arrays.asList("b/.B", "a/.A"));
        Map<String, Integer> r = Order.ranks(o, DOCK);
        assertEquals(Integer.valueOf(0), r.get("b/.B"));
        assertEquals(Integer.valueOf(1), r.get("a/.A"));
    }

    @Test
    public void changedDockAppsDropTheSavedOrder() {
        Order o = new Order(Order.dockKey(DOCK), Arrays.asList("b/.B", "a/.A"));
        assertTrue(Order.ranks(o, Arrays.asList("com.netflix.ninja", "com.plexapp.android")).isEmpty());
        assertTrue(Order.ranks(o, Collections.singletonList("com.plexapp.android")).isEmpty());
        assertTrue(Order.ranks(null, DOCK).isEmpty());
    }

    @Test
    public void savedTilesComeFirstAndNewOnesKeepTheirOrderAfter() {
        Order o = new Order(Order.dockKey(DOCK), Arrays.asList("c/.C", "a/.A", "gone/.Gone"));
        Map<String, Integer> r = Order.ranks(o, DOCK);
        // "n" and "m" are new installs: unranked, so the usual rule (here: list order) decides between them.
        List<String> tiles = new ArrayList<>(Arrays.asList("m/.M", "a/.A", "n/.N", "c/.C"));
        List<String> usual = new ArrayList<>(tiles);
        tiles.sort((x, y) -> {
            int c = Order.compare(r, x, y);
            return c != 0 ? c : Integer.compare(usual.indexOf(x), usual.indexOf(y));
        });
        assertEquals(Arrays.asList("c/.C", "a/.A", "m/.M", "n/.N"), tiles);
    }

    @Test
    public void legacyEncodingRemainsUnchangedAndDoesNotMigrateOnRead() {
        String legacy = "tvhome-order 1\ndock a,b\nb/.B\na/.A\n";
        Order order = Order.decode(legacy);
        assertNotNull(order);
        assertNull(order.dockSlots);
        assertEquals(order.keys, order.apps);
        assertEquals(legacy, Order.encode(order));
    }

    @Test
    public void explicitSlotsRoundTripIncludingInteriorAndTrailingHoles() {
        Order order = new Order(Order.signature(DOCK),
                Arrays.asList(null, "b/.B", null, "a/.A", null, null),
                Arrays.asList("tv.input.hdmi1/Port1", "c/.C"));
        Order back = Order.decode(Order.encode(order));
        assertNotNull(back);
        assertEquals(order.dockSlots, back.dockSlots);
        assertEquals(order.apps, back.apps);
        assertEquals(Arrays.asList("b/.B", "a/.A", "tv.input.hdmi1/Port1", "c/.C"), back.keys);
        assertTrue(Order.matches(back, DOCK));
        assertTrue(Order.matches(back, Order.dockKey(DOCK)));
        assertFalse(Order.matches(back, "changed"));
        assertEquals(Integer.valueOf(2), Order.ranks(back, DOCK).get("tv.input.hdmi1/Port1"));
        assertTrue(Order.ranks(back, Collections.singletonList("changed")).isEmpty());
    }

    @Test
    public void explicitEmptyLayoutIsDifferentFromLegacyEmptyOrder() {
        Order empty = Order.decode(Order.encode(new Order("", Arrays.asList(null, null), Collections.emptyList())));
        assertNotNull(empty);
        assertEquals(Arrays.asList(null, null), empty.dockSlots);
        assertTrue(empty.apps.isEmpty());
        assertTrue(empty.keys.isEmpty());
        Order noSlots = Order.decode("tvhome-order 2\ndock \nslots 0\n");
        assertNotNull(noSlots);
        assertTrue(noSlots.dockSlots.isEmpty());
    }

    @Test
    public void slotMarkerCannotCollideWithAKey() {
        Order order = new Order("", Arrays.asList("slot", null, "-"), Arrays.asList("app", "slots 4"));
        Order back = Order.decode(Order.encode(order));
        assertNotNull(back);
        assertEquals(order.dockSlots, back.dockSlots);
        assertEquals(order.apps, back.apps);
    }

    @Test
    public void explicitConstructorDefensivelyCopiesAndKeepsEachKeyOnce() {
        List<String> slots = new ArrayList<>(Arrays.asList("a/.A", "a/.A", null));
        List<String> apps = new ArrayList<>(Arrays.asList("a/.A", "b/.B", "b/.B"));
        Order order = new Order("", slots, apps);
        slots.clear(); apps.clear();
        assertEquals(Arrays.asList("a/.A", null, null), order.dockSlots);
        assertEquals(Collections.singletonList("b/.B"), order.apps);
        assertEquals(Arrays.asList("a/.A", "b/.B"), order.keys);
    }

    @Test
    public void malformedExplicitLayoutsNeverGetSilentlyNormalized() {
        String prefix = "tvhome-order 2\ndock x\n";
        for (String tail : Arrays.asList(
                "slots -1\n", "slots +1\nslot a/.A\n", "slots 01\nslot a/.A\n",
                "slots 999999999999999\n", "slots 2\nslot a/.A\n",
                "slots 1\nslot \n", "slots 1\nslot   \n", "slots 1\nslot  a/.A\n",
                "slots 1\nslot a/.A \n", "slots 1\napp a/.A\n",
                "slots 2\nslot a/.A\nslot a/.A\n",
                "slots 1\nslot a/.A\napp a/.A\n",
                "slots 0\napp a/.A\napp a/.A\n", "slots 0\napp \n",
                "slots 0\n\napp a/.A\n", "slots 0\nslot\n", "slots 0\n\n")) {
            assertNull(tail, Order.decode(prefix + tail));
        }
        assertNull(Order.decode(prefix + "slots 0\napp a/.A\r\n"));
    }
}
