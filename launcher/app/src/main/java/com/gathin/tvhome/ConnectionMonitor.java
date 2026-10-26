package com.gathin.tvhome;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
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
        final String label, status, ipAddress;

        private Snapshot(Transport transport, boolean connected, boolean validated, boolean known,
                         boolean vpn, String label, String status, String ipAddress) {
            this.transport = transport; this.connected = connected; this.validated = validated;
            this.known = known; this.vpn = vpn; this.label = label; this.status = status; this.ipAddress = ipAddress;
        }
        private static Snapshot checking() {
            return new Snapshot(Transport.OTHER, false, false, false, false, "Checking", "Checking connection", "");
        }
        private static Snapshot unavailable() {
            return new Snapshot(Transport.OTHER, false, false, false, false, "Unavailable", "Connection status unavailable", "");
        }
        private static Snapshot offline() {
            return new Snapshot(Transport.OFFLINE, false, false, true, false, "Offline", "No network connection", "");
        }
        private boolean sameState(Snapshot other) {
            return other != null && transport == other.transport && connected == other.connected
                    && validated == other.validated && known == other.known && vpn == other.vpn
                    && label.equals(other.label) && status.equals(other.status) && ipAddress.equals(other.ipAddress);
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
    private ConnectivityManager.NetworkCallback defaults, networks;
    private boolean defaultRegistered, networksRegistered, started;
    private Network defaultNetwork;
    private final Map<Network, State> states = new HashMap<>();

    private static final class State {
        NetworkCapabilities capabilities;
        LinkProperties links;
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
        if (state == null) { state = new State(); states.put(network, state); }
        return state;
    }

    private void startOnWorker() {
        if (!requested || started) return;
        final long generation = ++epoch;
        try {
            manager = context.getSystemService(ConnectivityManager.class);
            if (manager == null) { publish(Snapshot.unavailable(), generation); return; }
            started = true;
            defaults = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    if (!live(generation)) return;
                    defaultNetwork = network;
                    // API 26+ delivers capabilities/link properties next; no racy synchronous query here.
                }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    if (!live(generation)) return;
                    defaultNetwork = network; state(network).capabilities = new NetworkCapabilities(caps);
                    publishCurrent(generation);
                }
                @Override public void onLinkPropertiesChanged(Network network, LinkProperties links) {
                    if (!live(generation)) return;
                    state(network).links = links; publishCurrent(generation);
                }
                @Override public void onLost(Network network) {
                    if (!live(generation) || !network.equals(defaultNetwork)) return;
                    defaultNetwork = null; publishCurrent(generation);
                }
            };
            networks = new ConnectivityManager.NetworkCallback() {
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    if (!live(generation)) return;
                    state(network).capabilities = new NetworkCapabilities(caps); publishCurrent(generation);
                }
                @Override public void onLinkPropertiesChanged(Network network, LinkProperties links) {
                    if (!live(generation)) return;
                    state(network).links = links; publishCurrent(generation);
                }
                @Override public void onLost(Network network) {
                    if (!live(generation)) return;
                    states.remove(network); publishCurrent(generation);
                }
            };
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
        if (manager != null) {
            if (defaultRegistered) try { manager.unregisterNetworkCallback(defaults); } catch (RuntimeException ignored) { }
            if (networksRegistered) try { manager.unregisterNetworkCallback(networks); } catch (RuntimeException ignored) { }
        }
        defaultRegistered = networksRegistered = false;
        defaults = networks = null; manager = null; defaultNetwork = null; states.clear();
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
    private void publishCurrent(long generation) {
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
        publish(new Snapshot(kind, true, validated, true, vpn, label, status, address(addressSource.links)), generation);
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
