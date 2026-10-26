package com.gathin.tvhome;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import static org.junit.Assert.*;

public class AppAdapterLayoutTest {
    @Test public void explicitGapsAndUnavailableDockKeysRemainFixedSlots(){
        assertEquals(Arrays.asList("a",null,"b",null,"c","d"),AppAdapter.layoutKeys(
                Arrays.asList("a","b","c","d"),Arrays.asList("a",null,"b","uninstalled"),
                Arrays.asList("c","d"),4,true));
    }
    @Test public void fewerColumnsKeepOverflowDockAppsBeforeTheAppArea(){
        assertEquals(Arrays.asList("a",null,"b","c","d"),AppAdapter.layoutKeys(
                Arrays.asList("a","b","c","d"),Arrays.asList("a",null,"b","c"),
                Collections.singletonList("d"),2,true));
    }
    @Test public void moreColumnsDoNotFillNewExplicitGapsFromApps(){
        assertEquals(Arrays.asList("a",null,null,null,"b","c"),AppAdapter.layoutKeys(
                Arrays.asList("a","b","c"),Collections.singletonList("a"),Arrays.asList("b","c"),4,true));
    }
    @Test public void plainGridOmitsDockPlaceholdersAndKeepsEachAppOnce(){
        assertEquals(Arrays.asList("b","a","c"),AppAdapter.layoutKeys(
                Arrays.asList("a","b","c"),Arrays.asList(null,"b","b"),Arrays.asList("a","b"),4,false));
    }
    @Test public void fillingAnEmptyDockDoesNotLeaveANullOrDropAnotherApp(){
        LayoutDraft draft=new LayoutDraft(Arrays.asList("a",null),Arrays.asList("b","c","d"));
        assertTrue(draft.move(3,1));
        List<String> mapped=AppAdapter.layoutKeys(Arrays.asList("a","b","c","d"),draft.dockSlots(),draft.apps(),2,true);
        assertEquals(Arrays.asList("a","c","b","d"),mapped);
        assertEquals(4,new HashSet<>(mapped).size());
    }
    @Test public void unknownDraftKeysAreSkippedAndUnmentionedExistingAppsAreRetained(){
        assertEquals(Arrays.asList(null,"b","c","a","d"),AppAdapter.layoutKeys(
                Arrays.asList("a","b","c","d"),Arrays.asList("uninstalled","b"),
                Arrays.asList("c","c","also-uninstalled"),2,true));
    }
}
