package com.example.cloudcustomerservice.embedding;

/**
 * 无外部依赖的向量计算工具。使用 double 累加，减少 float 乘法溢出与舍入误差。
 * 余弦比较的是方向而非长度；此处零向量约定得分为 0，上游服务仍会拒绝模型零向量。
 */
public final class VectorMath {
    /**
     * 纯静态工具无需实例，私有构造器阻止误用。
     */
    private VectorMath() { }

    /**
     * 计算点积除以两向量模长乘积；每个分量须有限且两向量维度一致。
     * 零向量按本地约定返回 0；最终夹到 [-1, 1] 以消除浮点误差造成的轻微越界。
     * @param left 左向量，非空且至少一维
     * @param right 与左向量等维的右向量
     * @return 方向相似度，不是概率
     */
    public static double cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            throw new IllegalArgumentException("Vectors must be nonempty and have matching dimensions");
        }
        // 点积衡量同向程度，两边平方和用于计算模长，统一以 double 累加。
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
