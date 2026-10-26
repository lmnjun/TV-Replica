package com.gathin.tvhome;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The owner's layout, kept atomically in the launcher's private files (order.txt).
 *
 * Saved tiles come first in this order; apps not in it (new installs) follow by the usual rule (dock_apps, cards,
 * alphabetical) and uninstalled ones simply drop out. The file also records the dock_apps it was made under: when
 * dock_apps changes (a rebuild or a pushed theme), the saved order is ignored, so the new dock takes effect until
 * the owner reorders again.
 *
 * Version 1 is the original flat order. Version 2 adds an explicit, fixed-size Dock containing nullable
 * slots and a separate Apps order. Both are tied to the theme's dock_apps signature. Loading never migrates
 * or writes a file; only an explicit save commits a new layout.
 */
final class Order {

    private static final String TAG = "TvHome.Order";
    static final String FILE_NAME = "order.txt";
    private static final String MAGIC = "tvhome-order 1";
    private static final String MAGIC_LAYOUT = "tvhome-order 2";
    private static final String DOCK = "dock ";
    private static final String SLOTS = "slots ";
    private static final String SLOT = "slot";
    private static final String APP = "app ";

    /** dock_apps the order was saved under, comma-joined (package names cannot contain commas). */
    final String dock;
    /** Alias making clear that this identifies the theme, not the user's chosen Dock contents. */
    final String signature;
    /** Null only for a legacy flat order. Null elements are intentional empty Dock slots. */
    final List<String> dockSlots;
    final List<String> apps;
    /** Nonempty component keys, in Dock-then-Apps order, each included once. */
    final List<String> keys;

    Order(String dock, List<String> keys) {
        this.dock = checkedSignature(dock);
        signature = dock;
        dockSlots = null;
        this.keys = immutable(uniqueKeys(keys, new HashSet<>()));
        apps = this.keys;
    }

    Order(String signature, List<String> dockSlots, List<String> apps) {
        this.dock = checkedSignature(signature);
        this.signature = signature;
        List<String> slots = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (dockSlots != null) for (String key : dockSlots) {
            if (key != null) requireKey(key);
            slots.add(key != null && seen.add(key) ? key : null);
        }
        this.dockSlots = immutable(slots);
        this.apps = immutable(uniqueKeys(apps, seen));
        List<String> flattened = new ArrayList<>();
        for (String key : slots) if (key != null) flattened.add(key);
        flattened.addAll(this.apps);
        keys = immutable(flattened);
    }

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static List<String> uniqueKeys(List<String> source, Set<String> seen) {
        List<String> result = new ArrayList<>();
        if (source != null) for (String key : source) {
            requireKey(key);
            if (seen.add(key)) result.add(key);
        }
        return result;
    }

    private static String checkedSignature(String signature) {
        if (signature == null || signature.indexOf('\n') >= 0 || signature.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Invalid Dock signature");
        return signature;
    }

    private static boolean validKey(String key) {
        return key != null && !key.trim().isEmpty() && key.equals(key.trim())
                && key.indexOf('\n') < 0 && key.indexOf('\r') < 0;
    }

    private static void requireKey(String key) {
        if (!validKey(key)) throw new IllegalArgumentException("Invalid tile key");
    }

    static String dockKey(List<String> dockApps) {
        return String.join(",", dockApps);
    }

    static String signature(List<String> dockApps) { return dockKey(dockApps); }

    static boolean matches(Order order, List<String> dockApps) {
        return dockApps != null && matches(order, dockKey(dockApps));
    }

    static boolean matches(Order order, String signature) {
        return order != null && order.dock.equals(signature);
    }

    static String encode(Order order) {
        StringBuilder sb = new StringBuilder(order.dockSlots == null ? MAGIC : MAGIC_LAYOUT)
                .append('\n').append(DOCK).append(order.dock).append('\n');
        if (order.dockSlots == null) {
            for (String k : order.keys) sb.append(k).append('\n');
        } else {
            sb.append(SLOTS).append(order.dockSlots.size()).append('\n');
            for (String key : order.dockSlots) {
                sb.append(SLOT);
                if (key != null) sb.append(' ').append(key);
                sb.append('\n');
            }
            for (String key : order.apps) sb.append(APP).append(key).append('\n');
        }
        return sb.toString();
    }

    /** The order in this text, or null when it is not one (wrong header, a blank or duplicate key). */
    static Order decode(String text) {
        if (text == null || text.indexOf('\r') >= 0) return null;
        String[] lines = text.split("\n", -1);
        if (lines.length < 2 || !lines[1].startsWith(DOCK)) return null;
        if (lines[0].equals(MAGIC_LAYOUT)) return decodeLayout(lines);
        if (!lines[0].equals(MAGIC)) return null;
        List<String> keys = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 2; i < lines.length; i++) {
            String k = lines[i];
            if (k.isEmpty()) {
                if (i == lines.length - 1) break;   // the trailing newline
                return null;
            }
            if (!validKey(k) || !seen.add(k)) return null;
            keys.add(k);
        }
        return new Order(lines[1].substring(DOCK.length()), keys);
    }

    private static Order decodeLayout(String[] lines) {
        if (lines.length < 3 || !lines[2].startsWith(SLOTS)) return null;
        String countText = lines[2].substring(SLOTS.length());
        if (!countText.matches("0|[1-9][0-9]*")) return null;
        int count;
        try { count = Integer.parseInt(countText); }
        catch (NumberFormatException e) { return null; }
        int end = lines.length;
        if (lines[end - 1].isEmpty()) end--;
        if (count > end - 3) return null;
        List<String> slots = new ArrayList<>(), apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < count; i++) {
            String line = lines[3 + i];
            if (line.equals(SLOT)) slots.add(null);
            else {
                if (!line.startsWith(SLOT + " ")) return null;
                String key = line.substring(SLOT.length() + 1);
                if (!validKey(key) || !seen.add(key)) return null;
                slots.add(key);
            }
        }
        for (int i = 3 + count; i < end; i++) {
            if (!lines[i].startsWith(APP)) return null;
            String key = lines[i].substring(APP.length());
            if (!validKey(key) || !seen.add(key)) return null;
            apps.add(key);
        }
        return new Order(lines[1].substring(DOCK.length()), slots, apps);
    }

    /**
     * Rank of each saved key (0 = first) when the order still applies to these dock_apps, else an empty map.
     * Tiles without a rank sort after every ranked one.
     */
    static Map<String, Integer> ranks(Order order, List<String> dockApps) {
        Map<String, Integer> r = new HashMap<>();
        if (!matches(order, dockApps)) return r;
        for (int i = 0; i < order.keys.size(); i++) r.put(order.keys.get(i), i);
        return r;
    }

    /** Sort step for two tiles: saved ones first in saved order, then 0 to fall through to the usual rule. */
    static int compare(Map<String, Integer> ranks, String keyA, String keyB) {
        Integer a = ranks.get(keyA), b = ranks.get(keyB);
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return Integer.compare(a, b);
    }

    // ---- file ------------------------------------------------------------------------------------

    static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    /** The saved order, or null when there is none or it does not decode (then it is ignored, not deleted). */
    static Order load(File file) {
        if (!file.exists()) return null;
        try {
            Order o = decode(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
            if (o == null) Log.w(TAG, file.getName() + " is not valid; ignored");
            return o;
        } catch (IOException e) {
            Log.w(TAG, "cannot read " + file.getName() + ": " + e);
            return null;
        }
    }

    /** Keep a recoverable original before the owner first saves the explicit-slot layout. */
    static void backupBeforeLayoutMigration(Context context, Order order) {
        if (order == null || order.dockSlots != null) return;
        File dir = context.getExternalFilesDir(null);
        if (dir == null) return;
        File backup = new File(dir, "layout-before-unified.txt");
        if (!backup.exists()) save(backup, order);
    }

    /** Writes the order atomically. False (and a log line) when the write failed. */
    static boolean save(File file, Order order) {
        AtomicFile af = new AtomicFile(file);
        FileOutputStream out = null;
        try {
            out = af.startWrite();
            out.write(encode(order).getBytes(StandardCharsets.UTF_8));
            af.finishWrite(out);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "cannot write " + file.getName() + ": " + e);
            if (out != null) af.failWrite(out);
            return false;
        }
    }
}
