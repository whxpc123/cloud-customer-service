package com.example.cloudcustomerservice;

import com.example.cloudcustomerservice.knowledge.management.KnowledgeEvaluationService;
import com.example.cloudcustomerservice.knowledge.ingestion.*;
import com.example.cloudcustomerservice.rag.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 不需要数据库的原件快照与评测指标契约测试。 */
class KnowledgeManagementTest {
    /** 原始字节不随调用者数组改变，粘贴正文仍保留原始换行。 */
    @Test void originalSnapshotHasDefensiveBytes(){
        byte[] data={1,2,3};var original=new KnowledgeOriginal(data,"正文");data[0]=9;byte[] fetched=original.bytes();fetched[1]=9;
        assertThat(original.bytes()).containsExactly(1,2,3);
        var p=new KnowledgePreparationService(new KnowledgeDocumentReaderFactory()).text("制度","1","原文\r\n正文",ChunkingOptions.defaults());
        assertThat(new String(p.original().bytes(),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("原文\r\n正文");
    }
    /** 自然语言拒绝不等于 NO_EVIDENCE；来源列表为空时不能算命中。 */
    @Test void generatedRefusalAndMissingSourcesDoNotMasqueradeAsPassed(){
        var test=new KnowledgeEvaluationService.Case("问题",true,List.of("expected"));
        var response=new AdvisorKnowledgeAnswerResponse("r","c","q",KnowledgeAnswerStatus.ANSWERED,"抱歉不能回答",List.of());
        var compared=KnowledgeEvaluationService.compare(1,test,response,10,null);
        assertThat(compared.refusalMatched()).isFalse();assertThat(compared.sourceHit()).isFalse();
        var failure=KnowledgeEvaluationService.compare(1,test,null,10,"网络失败");
        assertThat(failure.refusalMatched()).isNull();assertThat(failure.sourceHit()).isNull();
    }
    /** 无期望来源不进入引用分母；题数和字段完整性在运行之前校验。 */
    @Test void missingExpectationsAndOversizedDatasetsAreExplicit(){
        var test=new KnowledgeEvaluationService.Case("问题",true,List.of());
        var response=new AdvisorKnowledgeAnswerResponse("r","c","q",KnowledgeAnswerStatus.NO_EVIDENCE,"无依据",List.of());
        assertThat(KnowledgeEvaluationService.compare(1,test,response,10,null).sourceHit()).isNull();
        var valid=new KnowledgeEvaluationService.CaseInput("问题",false,List.of());
        assertThatIllegalArgumentException().isThrownBy(()->KnowledgeEvaluationService.validate(new KnowledgeEvaluationService.Request("x",Collections.nCopies(21,valid))));
        assertThatIllegalArgumentException().isThrownBy(()->KnowledgeEvaluationService.validate(new KnowledgeEvaluationService.Request("x",List.of(new KnowledgeEvaluationService.CaseInput("问题",null,List.of())))));
    }
}
