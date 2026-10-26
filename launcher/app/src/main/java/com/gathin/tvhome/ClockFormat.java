package com.gathin.tvhome;

import android.content.Context;
import android.widget.TextClock;
import android.view.View;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** One local time-format choice for Home, canvas scenes and the screensaver. */
final class ClockFormat {
    static final String KEY="clock_format",H24="24h",H12="12h";
    private ClockFormat() { }

    static boolean is24Hour(Context context){
        return !H12.equals(context.getSharedPreferences(Config.SETTINGS_NAME,Context.MODE_PRIVATE).getString(KEY,H24));
    }
    static String pattern(Context context){return is24Hour(context)?"HH:mm":"h:mm a";}
    static void apply(TextClock clock){
        if(clock==null)return;
        String pattern=pattern(clock.getContext());
        clock.setFormat12Hour(pattern);clock.setFormat24Hour(pattern);
    }
    /** Split time/period layout used by the drifting local clock. */
    static void apply(TextClock clock,TextClock period){
        if(clock==null)return;
        boolean hour24=is24Hour(clock.getContext());
        String pattern=hour24?"HH:mm":"h:mm";
        clock.setFormat12Hour(pattern);clock.setFormat24Hour(pattern);
        if(period!=null){
            period.setFormat12Hour(hour24?"":"a");period.setFormat24Hour(hour24?"":"a");
            period.setVisibility(hour24?View.GONE:View.VISIBLE);
        }
    }
    static String format(Context context,long millis){return parts(context,millis).text();}
    static Parts parts(Context context,long millis){
        return parts(millis,is24Hour(context),TimeZone.getDefault(),Locale.getDefault());
    }

    static final class Parts {
        final int hour,minute;
        final boolean is24Hour;
        final String hourText,minuteText,amPm;
        Parts(int hour,int minute,boolean is24Hour,String amPm){
            this.hour=hour;this.minute=minute;this.is24Hour=is24Hour;this.amPm=amPm;
            hourText=is24Hour?twoDigits(hour):Integer.toString(hour);minuteText=twoDigits(minute);
        }
        String text(){return hourText+":"+minuteText+(is24Hour?"":" "+amPm);}
    }
    /** Android-free formatting core, also used to verify timezone and noon/midnight boundaries. */
    static Parts parts(long millis,boolean hour24,TimeZone zone,Locale locale){
        Calendar calendar=Calendar.getInstance(zone,locale);calendar.setTimeInMillis(millis);
        int hour=calendar.get(Calendar.HOUR_OF_DAY),minute=calendar.get(Calendar.MINUTE);
        return new Parts(hour24?hour:(hour+11)%12+1,minute,hour24,hour24?"":hour<12?"AM":"PM");
    }
    private static String twoDigits(int value){return value<10?"0"+value:Integer.toString(value);}
}
