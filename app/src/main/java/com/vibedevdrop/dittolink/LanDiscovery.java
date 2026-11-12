package com.vibedevdrop.dittolink;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Finds Ditto listeners on the phone's directly connected private IPv4 networks. */
final class LanDiscovery {
    private static final int DITTO_PORT = 23443;
    private static final int MAX_HOSTS = 512;
    private static final int THREADS = 24;
    private static final int CONNECT_TIMEOUT_MS = 400;

    static final class Device {
        final String name;
        final String type;
        final String ip;

        Device(String name, String type, String ip) {
            this.name = name;
            this.type = type;
            this.ip = ip;
        }
    }

    static List<Device> scan() throws Exception {
        Set<String> targets = new LinkedHashSet<>();
        List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
        for (NetworkInterface network : interfaces) {
            if (!network.isUp() || network.isLoopback() || !isLanInterface(network.getName())) continue;
            for (var address : network.getInterfaceAddresses()) {
                InetAddress ip = address.getAddress();
                short prefix = address.getNetworkPrefixLength();
                if (!(ip instanceof Inet4Address) || !ip.isSiteLocalAddress() || prefix < 1 || prefix > 32) continue;
                long host = ipv4ToLong(ip.getAddress());
                long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
                long networkAddress = host & mask;
                long broadcast = networkAddress | (~mask & 0xFFFFFFFFL);
                long first = prefix < 31 ? networkAddress + 1 : networkAddress;
                long last = prefix < 31 ? broadcast - 1 : broadcast;
                if (last - first + 1 > MAX_HOSTS) throw new IllegalArgumentException("This Wi-Fi network is too large to scan safely.");
                for (long candidate = first; candidate <= last; candidate++) {
                    if (candidate != host) targets.add(longToIpv4(candidate));
                }
            }
        }
        if (targets.isEmpty()) throw new IllegalStateException("No connected Wi-Fi or Ethernet IPv4 network was found.");
        if (targets.size() > MAX_HOSTS) throw new IllegalArgumentException("More than 512 local addresses were found; scan a smaller network.");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        ExecutorCompletionService<Device> completion = new ExecutorCompletionService<>(pool);
        try {
            for (String target : targets) completion.submit(() -> probe(target));
            List<Device> found = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                Future<Device> result = completion.take();
                Device device = result.get();
                if (device != null) found.add(device);
            }
            found.sort((a, b) -> a.ip.compareTo(b.ip));
            return found;
        } finally {
            pool.shutdownNow();
        }
    }

    static String reachableRoute(PcProfileStore.Profile profile) {
        for (String host : profile.hosts()) {
            if (isReachable(host)) return host;
        }
        return null;
    }

    static boolean isReachable(String host) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, DITTO_PORT), CONNECT_TIMEOUT_MS);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Device probe(String ip) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, DITTO_PORT), CONNECT_TIMEOUT_MS);
            String name = resolveName(ip);
            return new Device(name, "PC · Ditto", ip);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String resolveName(String ip) {
        try {
            String name = InetAddress.getByName(ip).getCanonicalHostName();
            if (name != null && !name.equals(ip) && !name.matches("[0-9.]+")) {
                int dot = name.indexOf('.');
                return (dot > 0 ? name.substring(0, dot) : name) + " PC";
            }
        } catch (Exception ignored) { }
        return "Ditto PC";
    }

    private static boolean isLanInterface(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("wlan") || lower.startsWith("wifi") || lower.startsWith("eth");
    }

    private static long ipv4ToLong(byte[] bytes) {
        return ((bytes[0] & 0xFFL) << 24) | ((bytes[1] & 0xFFL) << 16)
                | ((bytes[2] & 0xFFL) << 8) | (bytes[3] & 0xFFL);
    }

    private static String longToIpv4(long value) {
        return ((value >>> 24) & 255) + "." + ((value >>> 16) & 255) + "."
                + ((value >>> 8) & 255) + "." + (value & 255);
    }

    private LanDiscovery() { }
}
