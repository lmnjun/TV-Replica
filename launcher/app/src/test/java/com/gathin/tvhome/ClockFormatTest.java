package com.gathin.tvhome;

import java.time.Instant;
import java.util.Locale;
import java.util.TimeZone;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClockFormatTest {
    @Test public void midnightAndNoonHaveCorrect12HourPeriods(){
        assertParts("2026-10-26T00:07:00Z",false,"UTC","12","07","AM");
        assertParts("2026-10-26T12:07:00Z",false,"UTC","12","07","PM");
        assertParts("2026-10-26T13:05:00Z",false,"UTC","1","05","PM");
        assertParts("2026-10-26T23:59:00Z",false,"UTC","11","59","PM");
    }
    @Test public void local24HourChoiceKeepsLeadingZeroAndHasNoPeriod(){
        assertParts("2026-10-26T00:07:00Z",true,"UTC","00","07","");
        assertParts("2026-10-26T13:05:00Z",true,"UTC","13","05","");
    }
    @Test public void timezoneIsAppliedBeforeChoosingHourAndPeriod(){
        assertParts("2026-10-25T23:30:00Z",false,"Asia/Kuala_Lumpur","7","30","AM");
        assertParts("2026-10-25T23:30:00Z",true,"Asia/Kuala_Lumpur","07","30","");
        assertParts("2026-10-26T00:30:00Z",false,"America/Los_Angeles","5","30","PM");
    }
    @Test public void explicitFormattingDoesNotInheritLocaleTimeConvention(){
        long instant=Instant.parse("2026-10-26T13:05:00Z").toEpochMilli();
        ClockFormat.Parts a=ClockFormat.parts(instant,false,TimeZone.getTimeZone("UTC"),Locale.CHINA);
        ClockFormat.Parts b=ClockFormat.parts(instant,true,TimeZone.getTimeZone("UTC"),Locale.US);
        assertEquals("1:05 PM",a.text());assertEquals("13:05",b.text());
    }
    private static void assertParts(String instant,boolean hour24,String zone,String hour,String minute,String period){
        ClockFormat.Parts value=ClockFormat.parts(Instant.parse(instant).toEpochMilli(),hour24,TimeZone.getTimeZone(zone),Locale.US);
        assertEquals(hour,value.hourText);assertEquals(minute,value.minuteText);assertEquals(period,value.amPm);assertEquals(hour24,value.is24Hour);
    }
}
