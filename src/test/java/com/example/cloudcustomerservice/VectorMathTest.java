package com.example.cloudcustomerservice;

import com.example.cloudcustomerservice.embedding.VectorMath;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 纯数学测试：使用已知方向与尺度的向量核对余弦公式，另测输入拒绝边界。
 * 浮点结果采用容差断言，避免把舍入误差当成业务失败。
 */

class VectorMathTest {
    /**
     * 用同向、正交、反向、零向量和大数值测试余弦性质，容差只用于非精确浮点结果。
     */
    @Test void knownDirectionsAndScaling() {
        assertThat(VectorMath.cosineSimilarity(new float[]{1,0},new float[]{2,0})).isCloseTo(1,within(1e-9));
        assertThat(VectorMath.cosineSimilarity(new float[]{1,0},new float[]{0,1})).isZero();
        assertThat(VectorMath.cosineSimilarity(new float[]{1,0},new float[]{-1,0})).isCloseTo(-1,within(1e-9));
        assertThat(VectorMath.cosineSimilarity(new float[]{0,0},new float[]{1,0})).isZero();
        assertThat(VectorMath.cosineSimilarity(new float[]{Float.MAX_VALUE,Float.MAX_VALUE},new float[]{1,1})).isCloseTo(1,within(1e-9));
    }
    /**
     * 空引用、空数组、维度不等及 NaN/无穷分量必须被拒绝，不能静默返回看似有效的分数。
     */
    @Test void invalidVectorsAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(()->VectorMath.cosineSimilarity(null,new float[]{1}));
        assertThatIllegalArgumentException().isThrownBy(()->VectorMath.cosineSimilarity(new float[0],new float[0]));
        assertThatIllegalArgumentException().isThrownBy(()->VectorMath.cosineSimilarity(new float[]{1},new float[]{1,0}));
        for(float value:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}) {
            assertThatIllegalArgumentException().isThrownBy(()->VectorMath.cosineSimilarity(new float[]{value},new float[]{1}));
        }
    }
}
