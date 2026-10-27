package com.gathin.tvhome;

/** Display-only normalisation of WifiInfo.getSSID(); no decoding, persistence or network access. */
final class WifiNetworkName {
    private WifiNetworkName() { }

    static String clean(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        boolean quoted = raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"';
        String name = quoted ? raw.substring(1, raw.length() - 1) : raw;
        if (name.trim().isEmpty() || "<unknown ssid>".equalsIgnoreCase(name.trim())
                || (!quoted && "0x".equalsIgnoreCase(name))) return "";
        StringBuilder safe = null;
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                if (safe == null) safe = new StringBuilder(name);
                safe.setCharAt(i, ' ');
            }
        }
        String result = safe == null ? name : safe.toString();
        return result.trim().isEmpty() ? "" : result;
    }
}
