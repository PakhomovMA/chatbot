package com.personal.chatbot.utils;

import java.util.Locale;
import java.util.regex.Pattern;

/** Filename hygiene for user uploads: no paths, no control characters, bounded length. */
public final class Filenames {

    public static final int MAX_LENGTH = 200;
    private static final Pattern UNSAFE = Pattern.compile("[\\p{Cntrl}<>:\"|?*\\\\]");

    private Filenames() {
    }

    /** Strips directory components and unsafe characters; returns an empty string if nothing usable remains. */
    public static String sanitize(String rawName) {
        String base = rawName.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        String clean = UNSAFE.matcher(base).replaceAll("_").strip();
        if (clean.startsWith(".")) {
            clean = "_" + clean.substring(1);
        }
        return clean.length() > MAX_LENGTH ? clean.substring(0, MAX_LENGTH) : clean;
    }

    /** Lower-case extension without the dot, or an empty string. */
    public static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Filename without its extension, or the whole name if it has none. */
    public static String baseName(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
