package com.gathin.tvhome;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class WeatherRequestGuardTest {
    @Test public void closedWidgetCannotOverwriteReplacementLocationOrForecast() {
        Object cacheLock = new Object();
        WeatherRequestGuard oldWidget = new WeatherRequestGuard(cacheLock);
        WeatherRequestGuard replacement = new WeatherRequestGuard(cacheLock);
        long oldRequest = oldWidget.begin(), newRequest = replacement.begin();
        AtomicInteger city = new AtomicInteger(), forecast = new AtomicInteger();
        oldWidget.close();
        assertTrue(replacement.runIfCurrent(newRequest, () -> { city.set(2); forecast.set(20); return true; }));
        assertFalse(oldWidget.runIfCurrent(oldRequest, () -> { city.set(1); return true; }));
        assertFalse(oldWidget.runIfCurrent(oldRequest, () -> { forecast.set(10); return true; }));
        assertEquals(2, city.get());
        assertEquals(20, forecast.get());
    }

    @Test public void aNewRequestRejectsOldResultsAndCallbacks() {
        WeatherRequestGuard guard = new WeatherRequestGuard(new Object());
        long old = guard.begin(), latest = guard.begin();
        assertFalse(guard.current(old));
        assertFalse(guard.runIfCurrent(old, () -> { fail("Stale result published"); return true; }));
        assertTrue(guard.current(latest));
        assertTrue(guard.runIfCurrent(latest, () -> true));
    }

    @Test public void publicationRunsInsideTheSharedCacheLock() {
        Object cacheLock = new Object();
        WeatherRequestGuard guard = new WeatherRequestGuard(cacheLock);
        assertTrue(guard.runIfCurrent(guard.begin(), () -> {
            assertTrue(Thread.holdsLock(cacheLock));
            return true;
        }));
    }

    @Test public void closeIsPermanentAndPreservesPublicationFailure() {
        WeatherRequestGuard guard = new WeatherRequestGuard(new Object());
        long request = guard.begin();
        assertFalse(guard.runIfCurrent(request, () -> false));
        guard.close(); guard.close();
        assertEquals(0, guard.begin());
        assertFalse(guard.current(request));
        assertFalse(guard.current(0));
    }
}
