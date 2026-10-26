package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Remote arranging scenarios: temporary moves must retain identity and remain exactly reversible. */
public class InlineArrangeSessionTest {
    @Test public void dockTileMovesIntoAnEmptyFixedPosition() {
        InlineArrangeSession session = new InlineArrangeSession(
                Arrays.asList("a", "b", null, null), Arrays.asList("c", "d"));
        assertTrue(session.pickUp(0));
        assertTrue(session.moveTo(2));
        assertEquals("a", session.movingKey());
        assertEquals(2, session.movingPosition());
        assertEquals(Arrays.asList(null, "b", "a", null), session.layout().dockSlots());
        assertEquals(Arrays.asList("c", "d"), session.layout().apps());
        assertTrue(session.changed());
    }

    @Test public void appsInsertInBothDirectionsWhileOtherAppsKeepTheirOrder() {
        InlineArrangeSession session = new InlineArrangeSession(
                Arrays.asList("a", "b"), Arrays.asList("c", "d", "e", "f"));
        assertTrue(session.pickUp(3));
        assertTrue(session.moveTo(5));
        assertEquals(Arrays.asList("c", "e", "f", "d"), session.layout().apps());
        assertEquals(5, session.movingPosition());
        assertTrue(session.moveTo(2));
        assertEquals(Arrays.asList("d", "c", "e", "f"), session.layout().apps());
        assertEquals("d", session.movingKey());
        assertEquals(2, session.movingPosition());
    }

    @Test public void crossingAreasSwapsWithAnOccupiedDockSlot() {
        InlineArrangeSession session = new InlineArrangeSession(
                Arrays.asList("a", "b"), Arrays.asList("c", "d", "e"));
        assertTrue(session.pickUp(0));
        assertTrue(session.moveTo(4));
        assertEquals(Arrays.asList("e", "b"), session.layout().dockSlots());
        assertEquals(Arrays.asList("c", "d", "a"), session.layout().apps());
        assertEquals("a", session.movingKey());
        assertEquals(4, session.movingPosition());
    }

    @Test public void emptyDockAbsorbsAnAppAndTheNextMoveFollowsItsIdentity() {
        InlineArrangeSession session = new InlineArrangeSession(
                Arrays.asList("a", null, "b"), Arrays.asList("c", "d", "e"));
        assertTrue(session.pickUp(4));
        assertTrue(session.moveTo(1));
        assertEquals(Arrays.asList("a", "d", "b"), session.layout().dockSlots());
        assertEquals(Arrays.asList("c", "e"), session.layout().apps());
        assertEquals(1, session.movingPosition());
        assertEquals(5, session.layout().size());
        assertTrue(session.moveTo(4));
        assertEquals(Arrays.asList("a", "e", "b"), session.layout().dockSlots());
        assertEquals(Arrays.asList("c", "d"), session.layout().apps());
        assertEquals("d", session.movingKey());
        assertEquals(4, session.movingPosition());
    }

    @Test public void droppingLetsTheUserPickAnotherTileWithoutCommittingThePreview() {
        InlineArrangeSession session = new InlineArrangeSession(
                Arrays.asList("a", "b", null), Arrays.asList("c", "d"));
        assertTrue(session.pickUp(0));
        assertTrue(session.moveTo(1));
        session.drop();
        assertNull(session.movingKey());
        assertEquals(-1, session.movingPosition());
        assertFalse(session.moveTo(2));
        assertTrue(session.changed());
        assertTrue(session.pickUp(4));
        assertTrue(session.moveTo(2));
        assertEquals("d", session.movingKey());
        assertEquals(Arrays.asList("b", "a", "d"), session.layout().dockSlots());
        assertEquals(Collections.singletonList("c"), session.layout().apps());
    }

    @Test public void cancelExactlyRestoresSlotsAndAppsAfterSeveralPickedTiles() {
        List<String> dock = Arrays.asList(null, "a", null, "b", "c", null);
        List<String> apps = Arrays.asList("d", "e", "f", "g");
        InlineArrangeSession session = new InlineArrangeSession(dock, apps);
        assertTrue(session.pickUp(7));
        assertTrue(session.moveTo(0));
        assertTrue(session.moveTo(8));
        session.drop();
        assertTrue(session.pickUp(3));
        assertTrue(session.moveTo(5));
        assertTrue(session.moveTo(6));
        assertTrue(session.changed());
        LayoutDraft restored = session.cancel();
        assertEquals(dock, restored.dockSlots());
        assertEquals(apps, restored.apps());
        assertNull(session.movingKey());
        assertEquals(-1, session.movingPosition());
        assertFalse(session.changed());
        assertFalse(session.moveTo(1));
        assertTrue(restored.move(1, 2));
        assertEquals(dock, session.layout().dockSlots());
    }

    @Test public void movingBackToTheOriginalPositionMakesTheSessionUnchanged() {
        InlineArrangeSession session = new InlineArrangeSession(
                Arrays.asList("a", null, "b"), Arrays.asList("c", "d", "e"));
        assertFalse(session.changed());
        assertTrue(session.pickUp(0));
        assertTrue(session.moveTo(2));
        assertTrue(session.changed());
        assertTrue(session.moveTo(0));
        assertFalse(session.changed());
        session.drop();
        assertTrue(session.pickUp(3));
        assertTrue(session.moveTo(5));
        assertTrue(session.changed());
        assertTrue(session.moveTo(3));
        assertFalse(session.changed());
        session.drop();
        assertFalse(session.changed());
    }

    @Test public void emptyOrInvalidPositionsCannotBePickedUpOrEraseACarriedTile() {
        InlineArrangeSession session = new InlineArrangeSession(
                Arrays.asList(null, "a", null), Collections.singletonList("b"));
        for (int index : new int[]{-1, 0, 2, 4, Integer.MAX_VALUE}) {
            assertFalse(session.pickUp(index));
            assertNull(session.movingKey());
        }
        assertTrue(session.pickUp(3));
        assertFalse(session.pickUp(0));
        assertEquals("b", session.movingKey());
        assertEquals(3, session.movingPosition());
        assertFalse(session.moveTo(-1));
        assertFalse(session.moveTo(4));
        assertFalse(session.moveTo(3));
        assertFalse(session.changed());
    }

    @Test public void callerListsAndReturnedSnapshotsCannotMutateTheSession() {
        List<String> dock = new ArrayList<>(Arrays.asList("a", null, "b"));
        List<String> apps = new ArrayList<>(Arrays.asList("c", "d"));
        InlineArrangeSession session = new InlineArrangeSession(dock, apps);
        dock.clear(); apps.clear();
        LayoutDraft external = session.layout();
        assertTrue(external.move(3, 1));
        assertEquals(Arrays.asList("a", null, "b"), session.layout().dockSlots());
        assertEquals(Arrays.asList("c", "d"), session.layout().apps());
        assertFalse(session.changed());
        assertTrue(session.pickUp(4));
        assertTrue(session.moveTo(1));
        assertEquals(Arrays.asList("a", null, "b"), session.cancel().dockSlots());
        assertEquals(Arrays.asList("c", "d"), session.layout().apps());
    }

    @Test public void emptySessionHasNothingToMoveAndCanBeCancelledRepeatedly() {
        InlineArrangeSession session = new InlineArrangeSession(null, null);
        assertFalse(session.pickUp(0));
        assertFalse(session.moveTo(0));
        assertEquals(-1, session.movingPosition());
        assertTrue(session.cancel().keys().isEmpty());
        assertTrue(session.cancel().dockSlots().isEmpty());
        assertFalse(session.changed());
    }
}
