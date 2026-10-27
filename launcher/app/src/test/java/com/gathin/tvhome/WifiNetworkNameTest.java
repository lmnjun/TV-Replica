package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class WifiNetworkNameTest {
    private static String quoted(String value) { return (char)34 + value + (char)34; }

    @Test public void redactedDisconnectedAndEmptyNamesStayUnavailable() {
        for (String value : new String[]{null, "", quoted(""), " ", "<unknown ssid>", "<UNKNOWN SSID>", quoted("<unknown ssid>"), "0x"})
            assertEquals("", WifiNetworkName.clean(value));
    }

    @Test public void removesOnlyTheFrameworksOuterQuotationMarks() {
        assertEquals("Example network", WifiNetworkName.clean(quoted("Example network")));
        String innerQuotes = "A " + (char)34 + "quoted" + (char)34 + " name";
        assertEquals(innerQuotes, WifiNetworkName.clean(quoted(innerQuotes)));
        String backslash = "Example" + (char)92 + "guest";
        assertEquals(backslash, WifiNetworkName.clean(quoted(backslash)));
    }

    @Test public void preservesVendorUnquotedNamesAndUndecodableHexRepresentation() {
        assertEquals("Example network", WifiNetworkName.clean("Example network"));
        assertEquals("e4b8ad", WifiNetworkName.clean("e4b8ad"));
    }

    @Test public void preservesUnicodeNamesWithoutLossyHexOrEscapeDecoding() {
        assertEquals("示例网络 · café", WifiNetworkName.clean(quoted("示例网络 · café")));
    }

    @Test public void preservesMeaningfulWhitespaceInsideAQuotedSsid() {
        assertEquals("  Example  ", WifiNetworkName.clean(quoted("  Example  ")));
    }

    @Test public void ordinaryWordsAreNotMistakenForAndroidRedactionTokens() {
        assertEquals("null", WifiNetworkName.clean(quoted("null")));
        assertEquals("unknown", WifiNetworkName.clean(quoted("unknown")));
        assertEquals("0x", WifiNetworkName.clean(quoted("0x")));
    }

    @Test public void controlCharactersCannotCreateExtraLinesInTheMenu() {
        assertEquals("Example guest", WifiNetworkName.clean(quoted("Example" + (char)10 + "guest")));
        assertEquals("", WifiNetworkName.clean(quoted("" + (char)10 + (char)9)));
    }
}
