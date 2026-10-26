package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HomeWindowTargetTest {
    private static final String GOOGLE="com.google.android.apps.tv.launcherx";
    private static final String RECOVERY="com.google.android.tungsten.setupwraith";

    @Test public void actualGoogleHomeStillReturnsToCleanHome(){
        assertTrue(redirect(GOOGLE,GOOGLE+".home.HomeActivity"));
        assertTrue(redirect(GOOGLE,".home.HomeActivity"));
        assertTrue(redirect(RECOVERY,RECOVERY+".RecoveryActivity"));
        assertTrue(redirect(RECOVERY,".RecoveryActivity"));
    }
    @Test public void googleNativeProfileChooserAndAccountFlowsStayOpen(){
        for(String activity:new String[]{".profile.chooser.ProfileChooserActivity",
                ".profile.ProfileActivity",".settings.SettingsActivity",".auth.AuthActivity",
                ".home.HomeSettingsActivity",".home.HomeActivityDialog","android.widget.FrameLayout"}){
            assertFalse(activity,redirect(GOOGLE,activity));
            if(activity.startsWith("."))assertFalse(activity,redirect(GOOGLE,GOOGLE+activity));
        }
    }
    @Test public void classSuffixOrOtherPackageDoesNotTriggerHomeTakeover(){
        assertFalse(redirect("other.launcher",GOOGLE+".home.HomeActivity"));
        assertFalse(redirect(GOOGLE,RECOVERY+".RecoveryActivity"));
        assertFalse(redirect(RECOVERY,".auth.RecoveryActivity"));
        assertFalse(redirect(RECOVERY,".AccountRecoveryActivity"));
        assertFalse(redirect(RECOVERY,".SettingsActivity"));
        assertFalse(redirect(GOOGLE,"HomeActivity"));
    }
    @Test public void missingWindowIdentityIsIgnored(){
        assertFalse(redirect(null,null));
        assertFalse(redirect(GOOGLE,null));
        assertFalse(redirect(null,GOOGLE+".home.HomeActivity"));
        assertFalse(redirect("",""));
    }
    private static boolean redirect(String pkg,String cls){
        return HomeKeyService.HomeWindowTarget.shouldRedirect(pkg,cls);
    }
}
