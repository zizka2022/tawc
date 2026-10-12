package me.phie.tawc.sigsysprobe;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

final class PrivateFiles {
    static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
    static String hash(File file, long maxBytes) throws Exception {
        return hash(file, maxBytes, System.nanoTime() + 10000000000L);
    }
    static String hash(File file, long maxBytes, long deadline) throws Exception {
        return hash(file, maxBytes, deadline, () -> false);
    }
    static String hash(File file, long maxBytes, long deadline, BooleanSupplier cancelled) throws Exception {
        if (cancelled.getAsBoolean()) throw new IOException("cancelled");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        byte[] bytes = new byte[65536];
        try (FileInputStream input = new FileInputStream(file)) {
            int size;
            while ((size = input.read(bytes)) != -1) {
                total += size;
                if (total > maxBytes || System.nanoTime() > deadline || cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new IOException("hash_budget");
                digest.update(bytes, 0, size);
            }
        }
        return hex(digest.digest());
    }
    static String read(File file, long cap) throws IOException {
        if (file.length() > cap) throw new IOException("log_budget");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = bytes(input, cap);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
    static byte[] bytes(InputStream input, long cap) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int size;
        while ((size = input.read(buffer)) != -1) {
            if (output.size() + size > cap) throw new IOException("read_budget");
            output.write(buffer, 0, size);
        }
        return output.toByteArray();
    }
    static void removeRun(File parent, File directory) throws IOException {
        // Paths are generated privately; do not follow symlinks during cleanup.
        if (!directory.getCanonicalFile().getParentFile().equals(parent.getCanonicalFile()))
            throw new IOException("cleanup_scope");
        try (Stream<java.nio.file.Path> paths = Files.walk(directory.toPath())) {
            java.nio.file.Path[] ordered = paths.sorted(Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new);
            for (java.nio.file.Path path : ordered) Files.deleteIfExists(path);
        }
    }
}
