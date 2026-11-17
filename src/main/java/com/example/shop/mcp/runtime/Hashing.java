package com.example.shop.mcp.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

final class Hashing {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Hashing() {
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /** Canonical JSON (sorted keys) so {a,b} and {b,a} hash the same. */
    static String canonical(Object v) {
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, val) -> sorted.put(String.valueOf(k), val));
            return sorted.entrySet().stream()
                    .map(e -> quote(e.getKey()) + ":" + canonical(e.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (v instanceof List<?> list) {
            return list.stream().map(Hashing::canonical).collect(Collectors.joining(",", "[", "]"));
        }
        if (v instanceof String s) {
            return quote(s);
        }
        return String.valueOf(v);
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
