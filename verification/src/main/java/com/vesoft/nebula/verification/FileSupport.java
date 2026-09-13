package com.vesoft.nebula.verification;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;

final class FileSupport {
    private FileSupport() { }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    static Path emptyDirectory(Path directory) throws Exception {
        Path path = directory.toAbsolutePath().normalize();
        Files.createDirectories(path);
        try (java.util.stream.Stream<Path> entries = Files.list(path)) {
            require(!entries.findAny().isPresent(), "Output directory must be empty: " + path);
        }
        return path;
    }

    static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }

    static String sha256(String text) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        }
        return out.toString();
    }

    static void writeJson(Path file, Object value) throws Exception {
        Files.write(file, JSON.toJSONString(value, SerializerFeature.PrettyFormat,
                SerializerFeature.MapSortField).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW);
    }

    static <T> T readJson(Path file, Class<T> type) throws Exception {
        require(Files.isRegularFile(file) && !Files.isSymbolicLink(file), "Missing file: " + file);
        return JSON.parseObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                .toJavaObject(type);
    }
}
