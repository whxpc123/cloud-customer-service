package com.example.cloudcustomerservice;

import com.example.cloudcustomerservice.embedding.VectorMath;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class VectorMathTest {
    @Test void knownDirectionsAndScaling() {
        assertThat(VectorMath.cosineSimilarity(new float[]{1,0},new float[]{2,0})).isCloseTo(1,within(1e-9));
        assertThat(VectorMath.cosineSimilarity(new float[]{1,0},new float[]{0,1})).isZero();
        assertThat(VectorMath.cosineSimilarity(new float[]{1,0},new float[]{-1,0})).isCloseTo(-1,within(1e-9));
        assertThat(VectorMath.cosineSimilarity(new float[]{0,0},new float[]{1,0})).isZero();
        assertThat(VectorMath.cosineSimilarity(new float[]{Float.MAX_VALUE,Float.MAX_VALUE},new float[]{1,1})).isCloseTo(1,within(1e-9));
    }
    @Test void invalidVectorsAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(()->VectorMath.cosineSimilarity(null,new float[]{1}));
        assertThatIllegalArgumentException().isThrownBy(()->VectorMath.cosineSimilarity(new float[0],new float[0]));
        assertThatIllegalArgumentException().isThrownBy(()->VectorMath.cosineSimilarity(new float[]{1},new float[]{1,0}));
        for(float value:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}) {
            assertThatIllegalArgumentException().isThrownBy(()->VectorMath.cosineSimilarity(new float[]{value},new float[]{1}));
        }
    }
}
