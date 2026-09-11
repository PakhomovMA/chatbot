package com.personal.chatbot.service.cache;

import com.personal.chatbot.utils.Hashes;

import java.util.List;

/**
 * A SHA-256 over a sequence of fields, each written with its length in front, so that two different
 * sequences never hash the same input: {@code "ab", "c"} and {@code "a", "bc"} stay apart, and so do a
 * filter of two documents and one document whose id happens to contain a separator.
 */
final class KeyDigest {

    private final StringBuilder canonical = new StringBuilder();

    /** @param kind what the digest identifies, so that digests of different kinds never share an input */
    KeyDigest(String kind) {
        add(kind);
    }

    KeyDigest add(String field) {
        canonical.append(field.length()).append(':').append(field).append('\n');
        return this;
    }

    KeyDigest add(long value) {
        return add(Long.toString(value));
    }

    KeyDigest add(double value) {
        return add(Double.toString(value));
    }

    /** A list as its size followed by its elements, in the order given. */
    KeyDigest addAll(List<String> fields) {
        add(fields.size());
        fields.forEach(this::add);
        return this;
    }

    String digest() {
        return Hashes.sha256(canonical.toString());
    }
}
