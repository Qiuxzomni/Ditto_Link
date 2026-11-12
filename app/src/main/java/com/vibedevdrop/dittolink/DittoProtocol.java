package com.vibedevdrop.dittolink;

import java.io.OutputStream;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.File;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.function.Consumer;
import java.util.function.Function;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/** Minimal Ditto peer sender for CF_UNICODETEXT clips. */
final class DittoProtocol {
    private static final int PORT = 23443;
    private static final Object LISTENER_LOCK = new Object();
    private static ServerSocket activeListener;
    private static final int HEADER_SIZE = 140;
    private static final int ROUND_COUNT = 100000;
    // Transfers up to this size arrive without asking; past it the user gets one confirmation and
    // everything after that in the same session flows without further prompts.
    private static final long LARGE_RECEIVE_LIMIT = 30L * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    static final class FilePart {
        final String name; final long size; final File file;
        FilePart(String name, File file) { this.name = name; this.file = file; this.size = file.length(); }
    }

    static final class FileReceiveResult {
        final java.util.List<File> files;
        final int expectedFileCount;
        final long bytesReceived;
        final boolean declined;
        final long pendingFileSize;

        FileReceiveResult(java.util.List<File> files, int expectedFileCount, long bytesReceived,
                          boolean declined, long pendingFileSize) {
            this.files = files;
            this.expectedFileCount = expectedFileCount;
            this.bytesReceived = bytesReceived;
            this.declined = declined;
            this.pendingFileSize = pendingFileSize;
        }
    }

    static void closeListener() {
        synchronized (LISTENER_LOCK) {
            if (activeListener == null) return;
            try { activeListener.close(); } catch (Exception ignored) { }
            activeListener = null;
        }
    }

    static final class FileTransferProgress {
        final String fileName;
        final int fileIndex;
        final int totalFiles;
        final long bytesRead;
        final long fileSize;

        FileTransferProgress(String fileName, int fileIndex, int totalFiles, long bytesRead, long fileSize) {
            this.fileName = fileName;
            this.fileIndex = fileIndex;
            this.totalFiles = totalFiles;
            this.bytesRead = bytesRead;
            this.fileSize = fileSize;
        }
    }

    interface LargeReceiveApproval {
        boolean confirm(long bytesAfterNextFile, long nextFileSize, int fileIndex, int totalFiles);
    }

    static void sendFileClip(String host, String password, String localIp, java.util.List<String> names) throws Exception {
        StringBuilder paths = new StringBuilder();
        for (String name : names) paths.append("C:\\DittoLink\\").append(name.replace('\\', '_').replace('/', '_')).append('\0');
        paths.append('\0');
        byte[] pathBytes = paths.toString().getBytes(StandardCharsets.UTF_16LE);
        ByteBuffer drop = ByteBuffer.allocate(20 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        drop.putInt(20).putInt(0).putInt(0).putInt(0).putInt(1).put(pathBytes);
        try (Socket socket = new Socket(InetAddress.getByName(host), PORT)) {
            OutputStream out = socket.getOutputStream();
            out.write(encrypt(sendInfo(0, "Ditto Link", localIp, "Files", 0, PORT), password));
            byte[] encryptedDrop = encrypt(drop.array(), password);
            out.write(encrypt(sendInfo(2, "", "", "CF_HDROP", encryptedDrop.length), password));
            out.write(encryptedDrop);
            out.write(encrypt(sendInfo(3, "", "", "CF_HDROP", encryptedDrop.length), password));
            out.write(encrypt(sendInfo(4, "", "", "", 0), password));
            out.write(encrypt(sendInfo(5, "", "", "", 0), password));
            out.flush();
        }
    }

    private static void writeFileClip(Socket socket, String password, String localIp,
                                      java.util.List<String> names) throws Exception {
        StringBuilder paths = new StringBuilder();
        for (String name : names) paths.append("C:\\DittoLink\\").append(name.replace('\\', '_').replace('/', '_')).append('\0');
        paths.append('\0');
        byte[] pathBytes = paths.toString().getBytes(StandardCharsets.UTF_16LE);
        ByteBuffer drop = ByteBuffer.allocate(20 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        drop.putInt(20).putInt(0).putInt(0).putInt(0).putInt(1).put(pathBytes);
        OutputStream out = socket.getOutputStream();
        out.write(encrypt(sendInfo(0, "Ditto Link", localIp, "Files", 0, PORT), password));
        byte[] encryptedDrop = encrypt(drop.array(), password);
        out.write(encrypt(sendInfo(2, "", "", "CF_HDROP", encryptedDrop.length), password));
        out.write(encryptedDrop);
        out.write(encrypt(sendInfo(3, "", "", "CF_HDROP", encryptedDrop.length), password));
        out.write(encrypt(sendInfo(4, "", "", "", 0), password));
        out.write(encrypt(sendInfo(5, "", "", "", 0), password));
        out.flush();
    }

    static String selectReachableHost(PcProfileStore.Profile profile) throws Exception {
        Exception lastError = null;
        for (String host : profile.hosts()) {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress(host, PORT), 700);
                return host;
            } catch (Exception error) { lastError = error; }
        }
        if (lastError != null) throw new java.io.IOException("Could not reach " + profile.name + " over LAN or Tailscale", lastError);
        throw new java.io.IOException("Add a LAN or Tailscale address for " + profile.name);
    }

    /** Fall back only for connection failures; replaying a partly-sent clipboard may duplicate it. */
    static String sendTextWithFallback(PcProfileStore.Profile profile, Function<String, String> advertisedIp,
                                       String text) throws Exception {
        Exception lastConnectError = null;
        for (String host : profile.hosts()) {
            Socket socket = new Socket();
            try { socket.connect(new InetSocketAddress(host, PORT), 900); }
            catch (Exception error) {
                lastConnectError = error;
                try { socket.close(); } catch (Exception ignored) { }
                continue;
            }
            try (socket) {
                socket.setSoTimeout(10000);
                writeTextClip(socket, profile.password, advertisedIp.apply(host), text);
                return host;
            }
        }
        throw new java.io.IOException("Could not connect over LAN or Tailscale", lastConnectError);
    }

    /** Fall back only for connection failures; replaying a partly-sent file clip may duplicate it. */
    static String sendFileClipWithFallback(PcProfileStore.Profile profile, Function<String, String> advertisedIp,
                                           java.util.List<String> names) throws Exception {
        Exception lastConnectError = null;
        for (String host : profile.hosts()) {
            Socket socket = new Socket();
            try { socket.connect(new InetSocketAddress(host, PORT), 900); }
            catch (Exception error) {
                lastConnectError = error;
                try { socket.close(); } catch (Exception ignored) { }
                continue;
            }
            try (socket) {
                writeFileClip(socket, profile.password, advertisedIp.apply(host), names);
                return host;
            }
        }
        throw new java.io.IOException("Could not connect over LAN or Tailscale", lastConnectError);
    }

    static void sendText(PcProfileStore.Profile profile, String localIp, String text) throws Exception {
        String host = selectReachableHost(profile);
        sendText(host, profile.password, text, localIp);
    }

    static void sendFileClip(PcProfileStore.Profile profile, String localIp, java.util.List<String> names) throws Exception {
        String host = selectReachableHost(profile);
        sendFileClip(host, profile.password, localIp, names);
    }

    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format(java.util.Locale.ROOT, "%02x", b)); return s.toString();
    }

    static void sendText(String host, String password, String text, String localIp) throws Exception {
        sendText(host, password, text, localIp, PORT);
    }

    static void sendText(String host, String password, String text, String localIp, int port) throws Exception {
        byte[] clip = (text + "\0").getBytes(StandardCharsets.UTF_16LE);
        try (Socket socket = new Socket(InetAddress.getByName(host), port)) {
            socket.setSoTimeout(10000);
            OutputStream out = socket.getOutputStream();
            out.write(encrypt(sendInfo(0, "Ditto Link", localIp, "Text", 0), password));
            byte[] encryptedClip = encrypt(clip, password);
            out.write(encrypt(sendInfo(2, "", "", "CF_UNICODETEXT", encryptedClip.length), password));
            out.write(encryptedClip);
            out.write(encrypt(sendInfo(3, "", "", "CF_UNICODETEXT", encryptedClip.length), password));
            out.write(encrypt(sendInfo(4, "", "", "", 0), password));
            out.write(encrypt(sendInfo(5, "", "", "", 0), password));
            out.flush();
        }
    }

    private static void writeTextClip(Socket socket, String password, String localIp, String text) throws Exception {
        byte[] clip = (text + "\0").getBytes(StandardCharsets.UTF_16LE);
        OutputStream out = socket.getOutputStream();
        out.write(encrypt(sendInfo(0, "Ditto Link", localIp, "Text", 0), password));
        byte[] encryptedClip = encrypt(clip, password);
        out.write(encrypt(sendInfo(2, "", "", "CF_UNICODETEXT", encryptedClip.length), password));
        out.write(encryptedClip);
        out.write(encrypt(sendInfo(3, "", "", "CF_UNICODETEXT", encryptedClip.length), password));
        out.write(encrypt(sendInfo(4, "", "", "", 0), password));
        out.write(encrypt(sendInfo(5, "", "", "", 0), password));
        out.flush();
    }

    static void listen(java.util.List<PcProfileStore.Profile> profiles, Consumer<String> onText,
                               Function<java.util.List<String>, java.util.List<FilePart>> requestedFiles,
                               File receiveDirectory, Consumer<FileReceiveResult> onFiles,
                               Consumer<FileTransferProgress> onFileProgress, Runnable onListening,
                               LargeReceiveApproval largeReceiveApproval) throws Exception {
        Map<InetAddress, java.util.List<PcProfileStore.Profile>> trustedPeers = new HashMap<>();
        for (PcProfileStore.Profile profile : profiles) {
            for (String host : profile.hosts()) {
                try {
                    for (InetAddress address : InetAddress.getAllByName(host))
                        trustedPeers.computeIfAbsent(address, key -> new java.util.ArrayList<>()).add(profile);
                } catch (Exception ignored) { }
            }
        }
        if (trustedPeers.isEmpty()) throw new IllegalArgumentException("No trusted PC address is configured");

        ServerSocket server = new ServerSocket();
        try (server) {
            server.setReuseAddress(true);
            synchronized (LISTENER_LOCK) {
                if (Thread.currentThread().isInterrupted()) return;
                server.bind(new InetSocketAddress(PORT), 8);
                activeListener = server;
            }
            try {
            onListening.run();
            server.setSoTimeout(1000);
            while (!Thread.currentThread().isInterrupted()) {
                Socket accepted;
                try { accepted = server.accept(); }
                catch (java.net.SocketTimeoutException retry) { continue; }
                try (Socket socket = accepted) {
                    java.util.List<PcProfileStore.Profile> matchingProfiles = trustedPeers.get(socket.getInetAddress());
                    if (matchingProfiles == null || matchingProfiles.isEmpty()) continue;
                    socket.setSoTimeout(30000);
                    String responseHost = socket.getInetAddress().getHostAddress();
                    int responsePort = PORT;
                    InputStream in = socket.getInputStream();
                    OutputStream out = socket.getOutputStream();
                    byte[] firstInfoBytes = null;
                    PcProfileStore.Profile acceptedProfile = null;
                    byte[] firstEncryptedInfo = readExact(in, 508);
                    for (PcProfileStore.Profile candidate : matchingProfiles) {
                        try {
                            byte[] decoded = decrypt(firstEncryptedInfo, candidate.password);
                            ByteBuffer first = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN);
                            if (first.getInt(0) != 360 || first.getInt(4) != 0) continue;
                            acceptedProfile = candidate;
                            firstInfoBytes = decoded;
                            break;
                        } catch (Exception ignored) { }
                    }
                    if (acceptedProfile == null) continue;
                    String password = acceptedProfile.password;
                    byte[] unicodeText = null;
                    byte[] dropFiles = null;
                    byte[] remoteDrop = null;
                    boolean firstMessage = true;
                    while (true) {
                        byte[] infoBytes;
                        if (firstMessage) { infoBytes = firstInfoBytes; firstMessage = false; }
                        else infoBytes = decrypt(readExact(in, 508), password);
                        ByteBuffer info = ByteBuffer.wrap(infoBytes).order(ByteOrder.LITTLE_ENDIAN);
                        info.position(4);
                        int type = info.getInt();
                        if (type == 0) {
                            int announcedPort = info.getShort(342) & 0xffff;
                            if (announcedPort > 0) responsePort = announcedPort;
                        }
                        if (type == 2) {
                            info.position(info.position() + 4 + 20 + 16);
                            String format = readString(info, 250);
                            info.position(info.position() + 2);
                            int length = info.getInt();
                            byte[] data = decrypt(readExact(in, length), password);
                            if ("CF_UNICODETEXT".equals(format)) unicodeText = data;
                            if ("CF_HDROP".equals(format)) dropFiles = data;
                            if ("Ditto Remote CF_HDROP".equals(format)) remoteDrop = data;
                        } else if (type == 4) {
                            if (unicodeText != null) {
                                String value = new String(unicodeText, StandardCharsets.UTF_16LE).replace("\0", "");
                                onText.accept(value);
                            }
                            unicodeText = null;
                        } else if (type == 6 && dropFiles != null) {
                            java.util.List<String> paths = parseDropFiles(dropFiles);
                            java.util.List<FilePart> files = requestedFiles.apply(paths);
                            sendFileData(out, password, files);
                            break;
                        } else if (type == 5) {
                            if (dropFiles != null && receiveDirectory != null) {
                                FileReceiveResult received = requestRemoteFiles(password, dropFiles, remoteDrop,
                                        responseHost, responsePort, receiveDirectory, largeReceiveApproval, onFileProgress);
                                onFiles.accept(received);
                            }
                            break;
                        }
                    }
                }
            }
            } finally {
                synchronized (LISTENER_LOCK) {
                    if (activeListener == server) activeListener = null;
                }
            }
        }
    }

    private static byte[] readExact(InputStream in, int count) throws Exception {
        if (count < 0 || count > 32 * 1024 * 1024) throw new IllegalArgumentException("Invalid Ditto data size");
        byte[] data = new byte[count];
        int offset = 0;
        while (offset < count) {
            int n = in.read(data, offset, count - offset);
            if (n < 0) throw new java.io.EOFException("Connection closed during clip");
            offset += n;
        }
        return data;
    }

    private static String readString(ByteBuffer b, int size) {
        byte[] bytes = new byte[size]; b.get(bytes);
        int end = 0; while (end < bytes.length && bytes[end] != 0) end++;
        return new String(bytes, 0, end, StandardCharsets.UTF_8).trim();
    }

    private static java.util.List<String> parseDropFiles(byte[] bytes) {
        java.util.List<String> paths = new java.util.ArrayList<>();
        if (bytes.length < 22) return paths;
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int offset = b.getInt(0); boolean wide = b.getInt(16) != 0;
        if (offset < 20 || offset >= bytes.length || !wide) return paths;
        StringBuilder item = new StringBuilder();
        for (int i = offset; i + 1 < bytes.length; i += 2) {
            char c = (char)((bytes[i] & 255) | ((bytes[i + 1] & 255) << 8));
            if (c == 0) { if (item.length() == 0) break; paths.add(item.toString()); item.setLength(0); }
            else item.append(c);
        }
        return paths;
    }

    private static FileReceiveResult requestRemoteFiles(String password, byte[] drop, byte[] remote,
                                                        String fallbackHost, int fallbackPort, File directory,
                                                        LargeReceiveApproval largeReceiveApproval,
                                                        Consumer<FileTransferProgress> onFileProgress) throws Exception {
        String host = fallbackHost;
        int port = fallbackPort > 0 ? fallbackPort : PORT;
        if (remote != null) {
            if (remote.length < 44) throw new IllegalArgumentException("Invalid Ditto remote file details");
            int ipEnd = 0; while (ipEnd < 25 && remote[ipEnd] != 0) ipEnd++;
            String remoteHost = new String(remote, 0, ipEnd, StandardCharsets.UTF_8).trim();
            if (!remoteHost.isEmpty()) host = remoteHost;
            int remotePort = ByteBuffer.wrap(remote).order(ByteOrder.LITTLE_ENDIAN).getShort(42) & 0xffff;
            if (remotePort > 0) port = remotePort;
        }
        if (host == null || host.trim().isEmpty()) throw new IllegalArgumentException("Ditto PC address is missing");
        java.util.List<File> received = new java.util.ArrayList<>();
        int expectedFileCount = 0;
        long bytesReceived = 0;
        boolean declined = false;
        long pendingFileSize = 0;
        boolean approvedLargeTransfer = false;
        directory.mkdirs();
        try (Socket socket = new Socket(InetAddress.getByName(host), port)) {
            socket.setSoTimeout(30000);
            InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream();
            out.write(encrypt(sendInfo(0, "Ditto Link", "", "Request files", 0), password));
            byte[] encryptedDrop = encrypt(drop, password);
            out.write(encrypt(sendInfo(2, "", "", "CF_HDROP", encryptedDrop.length), password));
            out.write(encryptedDrop);
            out.write(encrypt(sendInfo(3, "", "", "CF_HDROP", encryptedDrop.length), password));
            out.write(encrypt(sendInfo(4, "", "", "", 0), password));
            out.write(encrypt(sendInfo(6, "", "", "", 0), password)); out.flush();

            while (true) {
                Info info = readInfo(in, password);
                if (info.type == 4) break;
                if (info.type == 0) {
                    expectedFileCount = Math.max(0, info.parameter1);
                    continue;
                }
                if (info.type != 2) continue;
                if (info.parameter1 < 0) throw new java.io.IOException("Invalid incoming file size");
                long fileSize = info.parameter1;
                long projectedSize = bytesReceived + fileSize;
                if (!approvedLargeTransfer && projectedSize > LARGE_RECEIVE_LIMIT) {
                    int fileIndex = received.size() + 1;
                    if (largeReceiveApproval == null || !largeReceiveApproval.confirm(
                            projectedSize, fileSize, fileIndex, expectedFileCount)) {
                        declined = true;
                        pendingFileSize = fileSize;
                        break;
                    }
                    approvedLargeTransfer = true;
                }
                String fileName = new File(info.description.replace('\\', '/')).getName();
                if (fileName.isEmpty()) fileName = "received-file";
                int fileIndex = received.size() + 1;
                if (onFileProgress != null)
                    onFileProgress.accept(new FileTransferProgress(fileName, fileIndex, expectedFileCount, 0, fileSize));
                File target = new File(directory, fileName);
                MessageDigest md5 = MessageDigest.getInstance("MD5");
                try (FileOutputStream file = new FileOutputStream(target)) {
                    byte[] block = new byte[65536]; int remaining = info.parameter1;
                    long fileBytesRead = 0;
                    while (remaining > 0) {
                        int n = Math.min(block.length, remaining); byte[] bytes = readExact(in, n);
                        file.write(bytes); md5.update(bytes); remaining -= n; fileBytesRead += n;
                        if (onFileProgress != null)
                            onFileProgress.accept(new FileTransferProgress(fileName, fileIndex, expectedFileCount,
                                    fileBytesRead, fileSize));
                    }
                }
                Info end = readInfo(in, password);
                if (end.type != 3) throw new java.io.IOException("Missing file transfer end marker");
                received.add(target);
                bytesReceived += fileSize;
            }
        }
        return new FileReceiveResult(received, expectedFileCount, bytesReceived, declined, pendingFileSize);
    }

    private static final class Info {
        int type, parameter1; String description;
    }

    private static Info readInfo(InputStream in, String password) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(decrypt(readExact(in, 508), password)).order(ByteOrder.LITTLE_ENDIAN);
        Info info = new Info(); info.type = b.getInt(4);
        byte[] desc = new byte[250]; System.arraycopy(b.array(), 48, desc, 0, desc.length);
        int end = 0; while (end < desc.length && desc[end] != 0) end++;
        info.description = new String(desc, 0, end, StandardCharsets.UTF_8).trim();
        info.parameter1 = b.getInt(300);
        return info;
    }

    private static void sendFileData(OutputStream out, String password, java.util.List<FilePart> files) throws Exception {
        out.write(encrypt(sendInfo(0, "", "", "", files.size()), password));
        for (FilePart file : files) {
            out.write(encrypt(sendInfo(2, "", "", file.name, (int)file.size), password));
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            try (InputStream input = new FileInputStream(file.file)) {
                byte[] buf = new byte[65536]; int n; long remaining = file.size;
                while (remaining > 0 && (n = input.read(buf, 0, (int)Math.min(buf.length, remaining))) != -1) {
                    out.write(buf, 0, n); md5.update(buf, 0, n); remaining -= n;
                }
                if (remaining != 0) throw new java.io.EOFException("File changed while sending");
            }
            byte[] end = sendInfo(3, "", "", "", 0);
            byte[] digest = hex(md5.digest()).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(digest, 0, end, 308, digest.length);
            out.write(encrypt(end, password));
        }
        out.write(encrypt(sendInfo(4, "", "", "", 0), password)); out.flush();
    }

    private static byte[] sendInfo(int type, String computer, String ip, String desc, int parameter) throws Exception {
        return sendInfo(type, computer, ip, desc, parameter, 0);
    }

    private static byte[] sendInfo(int type, String computer, String ip, String desc, int parameter, int respondPort) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(360).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(360).putInt(type).putInt(1);
        putString(b, ip, 20); putString(b, computer, 16); putString(b, desc, 250);
        b.position(b.position() + 2);
        b.putInt(parameter).putInt(0); b.position(b.position() + 32);
        b.put((byte)1).put((byte)0).putShort((short)respondPort); b.position(b.position() + 16);
        return b.array();
    }

    private static void putString(ByteBuffer b, String value, int size) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(bytes.length, size - 1);
        b.put(bytes, 0, n); b.position(b.position() + size - n);
    }

    private static byte[] encrypt(byte[] input, String password) throws Exception {
        byte[] masterSeed = random(16), iv = random(16), seed2 = random(32);
        byte[] masterKey = sha256(password.getBytes(StandardCharsets.UTF_8));
        Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding");
        ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(seed2, "AES"));
        for (int i = 0; i < ROUND_COUNT; i++) masterKey = ecb.doFinal(masterKey);
        byte[] finalKey = sha256(join(masterSeed, sha256(masterKey)));
        byte[] contentHash = sha256(input);
        ByteBuffer h = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        h.position(32); h.putInt(0x139C5AFE).putInt(0xBF3562DA);
        h.put(masterSeed).put(iv).put(contentHash).put(seed2).putInt(ROUND_COUNT);
        byte[] header = h.array();
        byte[] headerHash = sha256(java.util.Arrays.copyOfRange(header, 32, HEADER_SIZE));
        System.arraycopy(headerHash, 0, header, 0, 32);
        Cipher cbc = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cbc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(finalKey, "AES"), new IvParameterSpec(iv));
        byte[] encrypted = cbc.doFinal(input);
        return join(header, encrypted);
    }

    private static byte[] decrypt(byte[] input, String password) throws Exception {
        if (input.length <= HEADER_SIZE) throw new IllegalArgumentException("Invalid encrypted Ditto message");
        byte[] header = java.util.Arrays.copyOfRange(input, 0, HEADER_SIZE);
        byte[] storedHash = java.util.Arrays.copyOfRange(header, 0, 32);
        byte[] calculatedHash = sha256(java.util.Arrays.copyOfRange(header, 32, HEADER_SIZE));
        if (!MessageDigest.isEqual(storedHash, calculatedHash)) throw new SecurityException("Invalid Ditto header");
        ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        if (h.getInt(32) != 0x139C5AFE || h.getInt(36) != 0xBF3562DA) throw new SecurityException("Invalid Ditto signature");
        byte[] seed = new byte[16], iv = new byte[16], contentHash = new byte[32], seed2 = new byte[32];
        h.position(40); h.get(seed); h.get(iv); h.get(contentHash); h.get(seed2);
        int rounds = h.getInt();
        if (rounds < 1 || rounds > ROUND_COUNT) throw new SecurityException("Invalid Ditto key rounds");
        byte[] transformed = sha256(transform(seed2, sha256(password.getBytes(StandardCharsets.UTF_8)), rounds));
        byte[] key = sha256(join(seed, transformed));
        Cipher cbc = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cbc.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] clear = cbc.doFinal(input, HEADER_SIZE, input.length - HEADER_SIZE);
        if (!MessageDigest.isEqual(contentHash, sha256(clear))) throw new SecurityException("Invalid Ditto content hash");
        return clear;
    }

    private static byte[] transform(byte[] seed, byte[] key, int rounds) throws Exception {
        Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding");
        ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(seed, "AES"));
        for (int i = 0; i < rounds; i++) key = ecb.doFinal(key);
        return key;
    }

    private static byte[] random(int n) { byte[] b = new byte[n]; RANDOM.nextBytes(b); return b; }
    private static byte[] sha256(byte[] b) throws Exception { return MessageDigest.getInstance("SHA-256").digest(b); }
    private static byte[] join(byte[] a, byte[] b) {
        byte[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length); return out;
    }
    private DittoProtocol() {}
}
