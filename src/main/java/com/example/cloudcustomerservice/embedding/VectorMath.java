package com.example.cloudcustomerservice.embedding;

public final class VectorMath {
    private VectorMath() { }

    public static double cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            throw new IllegalArgumentException("Vectors must be nonempty and have matching dimensions");
        }
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            double a = left[i], b = right[i];
            if (!Double.isFinite(a) || !Double.isFinite(b)) {
                throw new IllegalArgumentException("Vector values must be finite");
            }
            dot += a * b; leftNorm += a * a; rightNorm += b * b;
        }
        if (leftNorm == 0 || rightNorm == 0) return 0;
        return Math.max(-1, Math.min(1, dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm))));
    }
}
