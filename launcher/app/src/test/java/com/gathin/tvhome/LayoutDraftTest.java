package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

/** Draft editing is reversible and preserves every app, including across empty Dock slots. */
public class LayoutDraftTest {
    @Test public void sameAreaMovesHaveTheExpectedDifferentMeaning() {
        LayoutDraft draft = new LayoutDraft(Arrays.asList("a", "b", null), Arrays.asList("c", "d", "e"));
        assertTrue(draft.move(0, 2));
        assertEquals(Arrays.asList(null, "b", "a"), draft.dockSlots());
        assertTrue(draft.move(3, 5));
        assertEquals(Arrays.asList("d", "e", "c"), draft.apps());
        assertTrue(draft.move(5, 3));
        assertEquals(Arrays.asList("c", "d", "e"), draft.apps());
    }

    @Test public void crossAreaSwapKeepsAllOtherAppsInOrder() {
        LayoutDraft draft = new LayoutDraft(Arrays.asList("a", "b"), Arrays.asList("c", "d", "e"));
        assertTrue(draft.move(3, 0));
        assertEquals(Arrays.asList("d", "b"), draft.dockSlots());
        assertEquals(Arrays.asList("c", "a", "e"), draft.apps());
        assertTrue(draft.move(0, 3));
        assertEquals(Arrays.asList("a", "b"), draft.dockSlots());
        assertEquals(Arrays.asList("c", "d", "e"), draft.apps());
    }

    @Test public void appEnteringEmptySlotNeverLeavesNullInApps() {
        LayoutDraft draft = new LayoutDraft(Arrays.asList(null, "b", null), Arrays.asList("c", "d", "e"));
        assertTrue(draft.move(4, 0));
        assertEquals(Arrays.asList("d", "b", null), draft.dockSlots());
        assertEquals(Arrays.asList("c", "e"), draft.apps());
        assertEquals(3, draft.dockSize());
        assertEquals(5, draft.size());
        assertEquals(0, draft.positionOf("d"));
        assertEquals(4, draft.positionOf("e"));
        assertNull(draft.keyAt(2));
    }

    @Test public void removingDockAppRetainsItsSlotAndAppIdentity() {
        LayoutDraft draft = new LayoutDraft(Arrays.asList("a", null, "b"), Collections.singletonList("c"));
        assertTrue(draft.removeFromDock(0));
        assertEquals(Arrays.asList(null, null, "b"), draft.dockSlots());
        assertEquals(Arrays.asList("c", "a"), draft.apps());
        assertFalse(draft.removeFromDock(0));
        assertEquals(4, draft.positionOf("a"));
    }

    @Test public void takingSnapshotsAndReadingValuesDoesNotMutateDraft() {
        LayoutDraft draft = new LayoutDraft(Arrays.asList("a", null, "b"), Arrays.asList("c", "d"));
        LayoutDraft snapshot = draft.snapshot();
        draft.dockSlots().clear(); draft.apps().clear(); draft.keys().clear();
        draft.move(3, 1);
        assertEquals(Arrays.asList("a", null, "b"), snapshot.dockSlots());
        assertEquals(Arrays.asList("c", "d"), snapshot.apps());
        draft.reset(snapshot);
        assertEquals(snapshot.dockSlots(), draft.dockSlots());
        assertEquals(snapshot.apps(), draft.apps());
        draft.reset(draft);
        assertEquals(snapshot.dockSlots(), draft.dockSlots());
        assertEquals(snapshot.apps(), draft.apps());
    }

    @Test public void duplicateSourceDataCannotCreateDuplicateTiles() {
        LayoutDraft draft = new LayoutDraft(Arrays.asList("a", "a", null), Arrays.asList("a", "b", null, "b", "c"));
        assertEquals(Arrays.asList("a", null, null), draft.dockSlots());
        assertEquals(Arrays.asList("b", "c"), draft.apps());
        assertEquals(Arrays.asList("a", "b", "c"), draft.keys());
    }

    @Test public void invalidBoundsAreHarmlessAndEmptyInputIsValid() {
        LayoutDraft draft = new LayoutDraft(Arrays.asList("a", null), Collections.singletonList("b"));
        for (int[] move : new int[][]{{-1, 0}, {0, -1}, {3, 0}, {0, 3}, {1, 1}}) {
            assertFalse(draft.move(move[0], move[1]));
        }
        assertFalse(draft.removeFromDock(-1)); assertFalse(draft.removeFromDock(2));
        assertNull(draft.keyAt(-1)); assertNull(draft.keyAt(3));
        assertEquals(-1, draft.positionOf(null)); assertEquals(-1, draft.positionOf("gone"));
        LayoutDraft empty = new LayoutDraft(null, null);
        assertEquals(0, empty.size()); assertFalse(empty.move(0, 1));
    }

    @Test public void invalidResetDoesNotPartiallyReplaceTheDraft() {
        LayoutDraft draft = new LayoutDraft(Arrays.asList("a", null), Collections.singletonList("b"));
        try {
            draft.reset(Arrays.asList("c", " "), Collections.singletonList("d"));
            fail("Expected invalid key to fail before replacing the draft");
        } catch (IllegalArgumentException expected) {
            assertEquals(Arrays.asList("a", null), draft.dockSlots());
            assertEquals(Collections.singletonList("b"), draft.apps());
        }
    }

    @Test public void longRemoteEditingSequenceConservesEveryAppAndFixedSlotCount() {
        List<String> slots = Arrays.asList("a", null, "b", null, "c", null);
        List<String> apps = new ArrayList<>();
        for (int i = 0; i < 20; i++) apps.add("app" + i);
        LayoutDraft draft = new LayoutDraft(slots, apps);
        Set<String> original = new HashSet<>(draft.keys());
        Random random = new Random(47120);
        for (int i = 0; i < 5000; i++) {
            if (random.nextInt(7) == 0) draft.removeFromDock(random.nextInt(6));
            else draft.move(random.nextInt(draft.size()), random.nextInt(draft.size()));
            assertEquals(6, draft.dockSize());
            assertEquals(original, new HashSet<>(draft.keys()));
            assertEquals(original.size(), draft.keys().size());
            assertFalse(draft.apps().contains(null));
            for (String key : original) assertEquals(key, draft.keyAt(draft.positionOf(key)));
        }
    }
}
