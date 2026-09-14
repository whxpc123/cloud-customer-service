package com.example.cloudcustomerservice;

import java.util.*;
import java.util.stream.IntStream;
import com.example.cloudcustomerservice.embedding.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 第六章向量适配与排序单元测试，使用小型已知向量计算确定性结果。
 * 检查批次上限、输入与输出对齐、跨批维度和异常响应；不依赖在线语义模型。
 */

@ExtendWith(OutputCaptureExtension.class)
class SemanticSimilarityServiceTest {
    private final EmbeddingModel model=mock(EmbeddingModel.class);
    private final TextEmbeddingService texts=new TextEmbeddingService(model);
    private final SemanticSimilarityService service=new SemanticSimilarityService(texts);

    /**
     * 两句比较只发一批请求，并从返回向量得出实际维度，不额外调用维度探测。
     */
    @Test void comparesOneBatchAndUsesActualDimensionsWithoutDimensionProbe() {
        when(model.embed(List.of("退货","买错"))).thenReturn(List.of(new float[]{1,0},new float[]{1,1}));
        var result=service.compare("退货","买错");
        assertThat(result.dimensions()).isEqualTo(2);
        assertThat(result.score()).isCloseTo(1/Math.sqrt(2),within(1e-9));
        verify(model).embed(List.of("退货","买错")); verifyNoMoreInteractions(model);
    }
    /**
     * 测试单文本便捷入口与底层向量结果一致，确认它复用批量适配而不改变向量内容。
     */
    @Test void singleTextEntryPointReturnsActualVector() {
        when(model.embed(List.of("测试文本"))).thenReturn(List.of(new float[]{1,2,3}));
        assertThat(texts.embed("测试文本")).containsExactly(1,2,3);
    }
    /**
     * 构造同分和重复候选，验证降序排列、同分稳定性以及原候选不被去重。
     */
    @Test void sortsDescendingPreservesDuplicateCandidatesAndStableTies() {
        when(model.embed(anyList())).thenReturn(List.of(new float[]{1,0},new float[]{0,1},new float[]{1,0},new float[]{1,0}));
        var result=service.rank("问题",List.of("物流","退货","退货"));
        assertThat(result.matches()).extracting(SemanticMatch::content).containsExactly("退货","退货","物流");
        assertThat(result.matches()).extracting(SemanticMatch::score).containsExactly(1.0,1.0,0.0);
    }
    /**
     * 一个问题加二十条候选需要三批，检查分批后各得分仍与对应文本对齐。
     */
    @Test void twentyCandidatesUseThreeBatchesAndKeepInputAlignment() {
        List<List<String>> batches=new ArrayList<>();
        when(model.embed(anyList())).thenAnswer(invocation->{
            List<String> input=invocation.getArgument(0);batches.add(List.copyOf(input));
            return input.stream().map(s->s.equals("query")||s.equals("20")?new float[]{1,0}:new float[]{0,1}).toList();
        });
        var candidates=IntStream.rangeClosed(1,20).mapToObj(String::valueOf).toList();
        var result=service.rank("query",candidates);
        assertThat(batches).extracting(List::size).containsExactly(10,10,1);
        var expectedInputs = new ArrayList<String>(); expectedInputs.add("query"); expectedInputs.addAll(candidates);
        assertThat(batches.stream().flatMap(List::stream).toList()).containsExactlyElementsOf(expectedInputs);
        assertThat(result.matches().get(0).content()).isEqualTo("20");
        verify(model,never()).dimensions();
    }
    /**
     * 在列表后部放入非法文本，断言整个列表先完成验证，前部不会提前请求模型。
     */
    @Test void validatesAllInputsBeforeAnyPaidCall() {
        for(String value:Arrays.asList(null,"","  ","x".repeat(2001))) {
            assertThatIllegalArgumentException().isThrownBy(()->service.compare(value,"ok"));
            assertThatIllegalArgumentException().isThrownBy(()->service.compare("ok",value));
            assertThatIllegalArgumentException().isThrownBy(()->service.rank("ok",Collections.singletonList(value)));
        }
        assertThatIllegalArgumentException().isThrownBy(()->service.rank("ok",null));
        assertThatIllegalArgumentException().isThrownBy(()->service.rank("ok",List.of()));
        assertThatIllegalArgumentException().isThrownBy(()->service.rank("ok",Collections.nCopies(21,"a")));
        verifyNoInteractions(model);
    }
    /**
     * 模拟数量异常、空向量、零向量及非有限数值，验证它们不能继续计算成正常相似度。
     */
    @Test void malformedProviderResultsNeverBecomeValidScores() {
        List<List<float[]>> responses=Arrays.asList(null,List.of(),List.of(new float[]{1}),
                Arrays.asList(null,new float[]{1}),List.of(new float[0],new float[0]),
                List.of(new float[]{1},new float[]{1,0}),List.of(new float[]{Float.NaN},new float[]{1}),
                List.of(new float[]{Float.POSITIVE_INFINITY},new float[]{1}),List.of(new float[]{0},new float[]{1}));
        for(var response:responses){
            when(model.embed(anyList())).thenReturn(response);
            assertThatThrownBy(()->service.compare("a","b")).isInstanceOf(EmbeddingUnavailableException.class);
        }
    }
    /**
     * 让后续批次返回不同维度，确认跨批检查拒绝混合向量空间。
     */
    @Test void inconsistentDimensionsAcrossBatchesFail() {
        when(model.embed(anyList())).thenReturn(Collections.nCopies(10,new float[]{1,0}),List.of(new float[]{1}));
        assertThatThrownBy(()->service.rank("q",Collections.nCopies(10,"a"))).isInstanceOf(EmbeddingUnavailableException.class);
    }
    /**
     * 使用日志捕获核对只记录数量、维度等统计，依赖故障不输出客户原文或供应商错误正文。
     */
    @Test void logsOnlyMetadataAndHidesProviderFailure(CapturedOutput output) {
        when(model.embed(anyList())).thenReturn(List.of(new float[]{1},new float[]{1}));
        service.compare("PRIVATE_INPUT_A","PRIVATE_INPUT_B");
        when(model.embed(anyList())).thenThrow(new IllegalStateException("PRIVATE_PROVIDER_ERROR"));
        assertThatThrownBy(()->service.compare("PRIVATE_INPUT_A","PRIVATE_INPUT_B")).isInstanceOf(EmbeddingUnavailableException.class)
                .hasMessage("Embedding service unavailable").hasNoCause();
        assertThat(output.getAll()).contains("[EMBEDDING RESULT]","dimensions=1","[EMBEDDING ERROR]")
                .doesNotContain("PRIVATE_INPUT_A","PRIVATE_INPUT_B","PRIVATE_PROVIDER_ERROR");
    }
}
