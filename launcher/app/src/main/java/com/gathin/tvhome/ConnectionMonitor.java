package com.gathin.tvhome;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Passive network status. UI consumers only read immutable cached data; all system IPC is off-main. */
final class ConnectionMonitor {
    enum Transport { ETHERNET, WIFI, OTHER, OFFLINE }
    interface Listener { void onConnectionChanged(Snapshot snapshot); }

    static final class Snapshot {
        final Transport transport;
        final boolean connected, validated, known, vpn;
        /** Empty when not using Wi-Fi, redacted by Android, or not yet available. Never persisted or logged. */
        final String ssid;
        final String label, status, ipAddress;

        private Snapshot(Transport transport, boolean connected, boolean validated, boolean known,
                         boolean vpn, String label, String status, String ipAddress, String ssid) {
            this.transport = transport; this.connected = connected; this.validated = validated;
            this.known = known; this.vpn = vpn; this.label = label; this.status = status; this.ipAddress = ipAddress;
            this.ssid = ssid;
        }
        private static Snapshot checking() {
            return new Snapshot(Transport.OTHER, false, false, false, false, "Checking", "Checking connection", "", "");
        }
        private static Snapshot unavailable() {
            return new Snapshot(Transport.OTHER, false, false, false, false, "Unavailable", "Connection status unavailable", "", "");
        }
        private static Snapshot offline() {
            return new Snapshot(Transport.OFFLINE, false, false, true, false, "Offline", "No network connection", "", "");
        }
        private boolean sameState(Snapshot other) {
            return other != null && transport == other.transport && connected == other.connected
                    && validated == other.validated && known == other.known && vpn == other.vpn
                    && label.equals(other.label) && status.equals(other.status) && ipAddress.equals(other.ipAddress)
                    && ssid.equals(other.ssid);
        }
    }

    private static ConnectionMonitor instance;
    static synchronized ConnectionMonitor get(Context context) {
        if (instance == null) instance = new ConnectionMonitor(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Listener> listeners = new ArrayList<>(); // Main-thread only.
    private volatile Snapshot snapshot = Snapshot.checking();
    private volatile boolean requested;
    private volatile long epoch;
    private Handler worker;
    // The following fields and callback payloads belong exclusively to the worker looper.
    private ConnectivityManager manager;
    private WifiManager wifiManager;
    private ConnectivityManager.NetworkCallback defaults, networks;
    private boolean defaultRegistered, networksRegistered, started;
    private Network defaultNetwork;
    private final Map<Network, State> states = new HashMap<>();
    private Network legacyNameNetwork;
    private String legacyWifiName = "";
    private boolean wifiRefreshQueued;
    private final Runnable wifiRefresh = () -> {
        wifiRefreshQueued = false;
        if (requested && started) refreshWifiNameOnWorker(epoch);
    };

    private static final class State {
        final Network network;
        NetworkCapabilities capabilities;
        LinkProperties links;
        State(Network network) { this.network = network; }
    }

    private ConnectionMonitor(Context context) { this.context = context; }
    Snapshot cachedSnapshot() { return snapshot; }

    /** Subscribe and unsubscribe on the UI thread. Neither operation performs system IPC. */
    void subscribe(Listener listener) {
        if (listener == null || listeners.contains(listener)) return;
        listeners.add(listener);
        if (!requested) { requested = true; worker().post(this::startOnWorker); }
        listener.onConnectionChanged(snapshot);
    }
    void unsubscribe(Listener listener) {
        listeners.remove(listener);
        if (listeners.isEmpty() && requested) { requested = false; worker().post(this::stopOnWorker); }
    }

    /** Recheck after returning from network/permission settings. Calling it performs no system IPC. */
    void refresh() {
        if (!requested) return;
        worker().post(() -> {
            if (!requested) return;
            if (!started) startOnWorker();
            else queueWifiNameRefresh();
        });
    }

    private Handler worker() {
        if (worker == null) {
            HandlerThread thread = new HandlerThread("ConnectionStatus", Process.THREAD_PRIORITY_BACKGROUND);
            thread.start(); worker = new Handler(thread.getLooper());
        }
        return worker;
    }

    private boolean live(long generation) { return requested && started && generation == epoch; }
    private State state(Network network) {
        State state = states.get(network);
        if (state == null) { state = new State(network); states.put(network, state); }
        return state;
    }

    private void startOnWorker() {
        if (!requested || started) return;
        final long generation = ++epoch;
        try {
            manager = context.getSystemService(ConnectivityManager.class);
            if (manager == null) { publish(Snapshot.unavailable(), generation); return; }
            started = true;
            defaults = withWifiLocation(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    if (!live(generation)) return;
                    if (!network.equals(defaultNetwork)) clearLegacyWifiName();
                    defaultNetwork = network;
                    // API 26+ delivers capabilities/link properties next; no racy synchronous query here.
                }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    if (!live(generation)) return;
                    if (network.equals(legacyNameNetwork) && !nameFromCapabilities(caps).isEmpty()) clearLegacyWifiName();
                    defaultNetwork = network; state(network).capabilities = new NetworkCapabilities(caps);
                    publishCurrent(generation);
                }
                @Override public void onLinkPropertiesChanged(Network network, LinkProperties links) {
                    if (!live(generation)) return;
                    state(network).links = links; publishCurrent(generation);
                }
                @Override public void onLost(Network network) {
                    if (!live(generation) || !network.equals(defaultNetwork)) return;
                    clearLegacyWifiName();
                    defaultNetwork = null; publishCurrent(generation);
                }
            });
            networks = withWifiLocation(new ConnectivityManager.NetworkCallback() {
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    if (!live(generation)) return;
                    if (network.equals(legacyNameNetwork) && !nameFromCapabilities(caps).isEmpty()) clearLegacyWifiName();
                    state(network).capabilities = new NetworkCapabilities(caps); publishCurrent(generation);
                }
                @Override public void onLinkPropertiesChanged(Network network, LinkProperties links) {
                    if (!live(generation)) return;
                    state(network).links = links; publishCurrent(generation);
                }
                @Override public void onLost(Network network) {
                    if (!live(generation)) return;
                    if (network.equals(legacyNameNetwork)) clearLegacyWifiName();
                    states.remove(network); publishCurrent(generation);
                }
            });
            manager.registerDefaultNetworkCallback(defaults, worker); defaultRegistered = true;
            // Passive observation includes VPNs; this never requests or binds to a network.
            manager.registerNetworkCallback(new NetworkRequest.Builder()
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networks, worker);
            networksRegistered = true;
            // One bounded bootstrap on the background thread; subsequent updates use callback payloads.
            defaultNetwork = manager.getActiveNetwork();
            for (Network network : manager.getAllNetworks()) {
                State state = state(network);
                state.capabilities = manager.getNetworkCapabilities(network);
                state.links = manager.getLinkProperties(network);
            }
            publishCurrent(generation);
        } catch (RuntimeException unavailable) {
            tearDown(); publish(Snapshot.unavailable(), generation);
        }
    }

    private void stopOnWorker() {
        if (requested) return;
        ++epoch; tearDown();
    }
    private void tearDown() {
        started = false;
        worker.removeCallbacks(wifiRefresh); wifiRefreshQueued = false;
        clearLegacyWifiName(); wifiManager = null;
        if (manager != null) {
            if (defaultRegistered) try { manager.unregisterNetworkCallback(defaults); } catch (RuntimeException ignored) { }
            if (networksRegistered) try { manager.unregisterNetworkCallback(networks); } catch (RuntimeException ignored) { }
        }
        defaultRegistered = networksRegistered = false;
        defaults = networks = null; manager = null; defaultNetwork = null; states.clear();
    }

    private static ConnectivityManager.NetworkCallback withWifiLocation(ConnectivityManager.NetworkCallback delegate) {
        return Build.VERSION.SDK_INT >= 31 ? Api31Callbacks.withLocation(delegate) : delegate;
    }

    /** Android 12+ otherwise redacts WifiInfo's SSID even when location permission is granted. */
    @android.annotation.TargetApi(31)
    private static final class Api31Callbacks {
        static ConnectivityManager.NetworkCallback withLocation(ConnectivityManager.NetworkCallback delegate) {
            return new ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                @Override public void onAvailable(Network network) { delegate.onAvailable(network); }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    delegate.onCapabilitiesChanged(network, caps);
                }
                @Override public void onLinkPropertiesChanged(Network network, LinkProperties links) {
                    delegate.onLinkPropertiesChanged(network, links);
                }
                @Override public void onLost(Network network) { delegate.onLost(network); }
            };
        }
    }

    private static Transport transport(NetworkCapabilities caps) {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return Transport.ETHERNET;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return Transport.WIFI;
        return Transport.OTHER;
    }
    private State physical(Transport preferred) {
        State best = null; int bestScore = -1;
        for (State state : states.values()) {
            NetworkCapabilities caps = state.capabilities;
            if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
            Transport kind = transport(caps);
            if (preferred != Transport.OTHER && kind != preferred) continue;
            int score = kind == Transport.ETHERNET ? 300 : kind == Transport.WIFI ? 200 : 100;
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 10;
            if (score > bestScore) { best = state; bestScore = score; }
        }
        return best;
    }
    private void publishCurrent(long generation) { publishCurrent(generation, true); }

    private void publishCurrent(long generation, boolean refreshWifiName) {
        if (!live(generation)) return;
        if (defaultNetwork == null) { publish(Snapshot.offline(), generation); return; }
        State current = states.get(defaultNetwork);
        if (current == null || current.capabilities == null) { publish(Snapshot.checking(), generation); return; }
        NetworkCapabilities caps = current.capabilities;
        boolean vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        Transport kind = transport(caps);
        State addressSource = current;
        if (vpn) {
            // VPN capabilities normally include the underlying transport. Older TV firmware can omit it:
            // an available physical network is preferable to falsely labelling a connected VPN offline.
            State underlying = physical(kind);
            if (underlying != null) {
                if (kind == Transport.OTHER) kind = transport(underlying.capabilities);
                addressSource = underlying;
            }
        }
        boolean validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        boolean captive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
        String label = kind == Transport.ETHERNET ? "LAN" : kind == Transport.WIFI ? "Wi-Fi" : "Network";
        String status = captive ? "Sign-in required" : validated ? "Connected · Internet available" : "Connected · Internet not verified";
        if (vpn) status += " · VPN";
        String ssid = "";
        if (kind == Transport.WIFI) {
            ssid = addressSource.network.equals(legacyNameNetwork)
                    ? legacyWifiName : nameFromCapabilities(addressSource.capabilities);
            // Older Android/TCL callbacks omit WifiInfo. Coalesce a single background query
            // after callback delivery; never query WifiManager from UI or on a rendering tick.
            if (refreshWifiName && nameFromCapabilities(addressSource.capabilities).isEmpty()) queueWifiNameRefresh();
        } else clearLegacyWifiName();
        publish(new Snapshot(kind, true, validated, true, vpn, label, status, address(addressSource.links), ssid), generation);
    }

    private static String nameFromCapabilities(NetworkCapabilities capabilities) {
        if (Build.VERSION.SDK_INT >= 29 && capabilities != null) {
            Object info = capabilities.getTransportInfo();
            if (info instanceof WifiInfo) return WifiNetworkName.clean(((WifiInfo)info).getSSID());
        }
        return "";
    }

    private void queueWifiNameRefresh() {
        if (!requested || !started || wifiRefreshQueued) return;
        wifiRefreshQueued = true; worker.post(wifiRefresh);
    }

    private void clearLegacyWifiName() { legacyNameNetwork = null; legacyWifiName = ""; }

    @SuppressWarnings("deprecation") // Required fallback on Android 11/TCL; API 31+ callbacks are preferred.
    private void refreshWifiNameOnWorker(long generation) {
        if (!live(generation)) return;
        State source = defaultNetwork == null ? null : states.get(defaultNetwork);
        if (source != null && source.capabilities != null
                && source.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            State underlying = physical(transport(source.capabilities));
            if (underlying != null) source = underlying;
        }
        clearLegacyWifiName();
        if (source != null && source.capabilities != null && transport(source.capabilities) == Transport.WIFI) {
            legacyNameNetwork = source.network;
            try {
                if (wifiManager == null) wifiManager = context.getSystemService(WifiManager.class);
                WifiInfo info = wifiManager == null ? null : wifiManager.getConnectionInfo();
                if (info != null && info.getSupplicantState() == SupplicantState.COMPLETED)
                    legacyWifiName = WifiNetworkName.clean(info.getSSID());
            } catch (RuntimeException unavailable) {
                // Missing permission, a disabled location toggle or vendor redaction means no name.
                // Preserve connection/IP state, and never persist or log location-sensitive metadata.
            }
        }
        publishCurrent(generation, false);
    }
    private static String address(LinkProperties links) {
        if (links == null) return "";
        String ipv6 = "";
        for (LinkAddress link : links.getLinkAddresses()) {
            InetAddress address = link.getAddress();
            if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isAnyLocalAddress()) continue;
            String text = address.getHostAddress(); // Numeric only; no DNS or external request.
            if (text == null) continue;
            if (address instanceof Inet4Address) return text;
            if (ipv6.isEmpty()) ipv6 = text;
        }
        return ipv6;
    }
    private void publish(Snapshot next, long generation) {
        main.post(() -> {
            if (!requested || generation != epoch || next.sameState(snapshot)) return;
            snapshot = next;
            for (Listener listener : new ArrayList<>(listeners)) listener.onConnectionChanged(next);
        });
    }
}
