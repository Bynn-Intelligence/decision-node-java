package com.bynn.decisionnode;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** JSON reading and writing. Numbers are kept as sent: floats as doubles, never rounded. */
final class Json {
    static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final TypeReference<Object> ANY = new TypeReference<>() {};

    private Json() {}

    static byte[] write(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("the request cannot be serialised as JSON: " + e.getOriginalMessage(), e);
        }
    }

    /** Parses JSON into maps, lists, strings, numbers, booleans and null. Throws IOException on bad input. */
    static Object read(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException("empty body");
        }
        return MAPPER.readValue(data, ANY);
    }

    /** Parses JSON, or returns null when the body is empty or not JSON. */
    static Object readOrNull(byte[] data) {
        try {
            return read(data);
        } catch (IOException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }

    static Double asDouble(Object value) {
        return value instanceof Number && !(value instanceof Boolean) ? ((Number) value).doubleValue() : null;
    }

    static Long asLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : null;
    }
}
