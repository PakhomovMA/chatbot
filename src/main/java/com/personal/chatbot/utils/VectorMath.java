package com.personal.chatbot.utils;

/** Small float-vector helpers shared by the embedding package and its tests. */
public final class VectorMath {

    private VectorMath() {
    }

    public static float[] normalized(float[] vector) {
        double sum = 0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        float[] out = new float[vector.length];
        if (sum == 0) {
            return out;
        }
        float inv = (float) (1.0 / Math.sqrt(sum));
        for (int i = 0; i < vector.length; i++) {
            out[i] = vector[i] * inv;
        }
        return out;
    }

    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector lengths differ: " + a.length + " vs " + b.length);
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return (na == 0 || nb == 0) ? 0 : dot / Math.sqrt(na * nb);
    }

    public static double norm(float[] a) {
        double sum = 0;
        for (float v : a) {
            sum += (double) v * v;
        }
        return Math.sqrt(sum);
    }
}
