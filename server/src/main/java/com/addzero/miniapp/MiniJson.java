package com.addzero.miniapp;

import java.util.Collection;
import java.util.Map;
import java.util.StringJoiner;

final class MiniJson {
    private MiniJson() {
    }

    static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return quote(text);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            return mapToJson(map);
        }
        if (value instanceof Collection<?> collection) {
            return collectionToJson(collection);
        }
        throw new IllegalArgumentException("Unsupported JSON type: " + value.getClass());
    }

    private static String mapToJson(Map<?, ?> map) {
        StringJoiner joiner = new StringJoiner(",", "{", "}");
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            joiner.add(quote(String.valueOf(entry.getKey())) + ":" + toJson(entry.getValue()));
        }
        return joiner.toString();
    }

    private static String collectionToJson(Collection<?> collection) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (Object item : collection) {
            joiner.add(toJson(item));
        }
        return joiner.toString();
    }

    private static String quote(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2);
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(ch);
            }
        }
        return builder.append('"').toString();
    }
}
