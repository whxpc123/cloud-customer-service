package com.example.cloudcustomerservice.aftersale;

import java.util.*;
import org.springframework.ai.vectorstore.*;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import com.example.cloudcustomerservice.ai.advisor.EvidenceRequiredAdvisor;
import static com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;

/** 取得授权订单事实后才检索；来源和版本精确过滤，不以相似的新版本替换历史订单适用规则。 */
@Service
@Profile("local & knowledge")
public class ApplicablePolicyRetriever {
    private final VectorStore store;
    public ApplicablePolicyRetriever(VectorStore store){this.store=store;}
    public List<PolicyEvidence> retrieve(Actor actor,OrderFacts facts,ReturnReason reason) {
        if(facts.policySourceId()==null||facts.policySourceId().isBlank()||facts.policyVersion()==null||facts.policyVersion().isBlank())return List.of();
        var b=new FilterExpressionBuilder();
        var filter=b.and(b.and(b.eq("tenantId",actor.tenantId()),b.eq("knowledgeBase","after-sales")),
                b.and(b.and(b.eq("status","PUBLISHED"),b.eq("language","zh-CN")),
                b.and(b.eq("sourceId",facts.policySourceId()),b.eq("sourceVersion",facts.policyVersion())))).build();
        // 只将受控商品类别、诉求与核验枚举用于向量化，不发送订单号、用户 ID、地址或手机号。
        String type=switch(Objects.toString(facts.productType(),"UNKNOWN")){
            case "ORDINARY"->"普通商品";case "ACTIVATED_SOFTWARE"->"已激活的软件";
            case "UNACTIVATED_SOFTWARE"->"未激活的软件";default->"特殊或未知商品（具体条件需核验）";};
        String claim=switch(reason){case QUALITY_ISSUE->"用户反馈质量问题，尚不表示已经核实";case CHANGE_OF_MIND->"个人原因无理由退货";default->"退货原因未明确";};
        String query="商品类型："+type+"。用户诉求："+claim+"。系统质量核验状态："+facts.qualityVerification()+"。检索退货条件、质量问题例外与申请前需核验事项。";
        var docs=store.similaritySearch(SearchRequest.builder().query(query).topK(4).similarityThreshold(.50).filterExpression(filter).build());
        if(docs==null||docs.size()>4)throw new IllegalStateException("Invalid policy results");
        if(EvidenceRequiredAdvisor.validateDocuments(docs,actor.tenantId())==0)return List.of();
        var evidence=new ArrayList<PolicyEvidence>();
        for(var d:docs){
            // 存储返回仍需复核来源/版本，防止配置错误越过适用性边界。
            if(!facts.policySourceId().equals(d.getMetadata().get("sourceId"))||!facts.policyVersion().equals(d.getMetadata().get("sourceVersion")))
                throw new IllegalStateException("Policy version mismatch");
            if(d.getText().length()>12000)throw new IllegalStateException("Policy evidence too large");
            evidence.add(new PolicyEvidence(d.getId(),facts.policySourceId(),facts.policyVersion(),d.getText()));
        }
        return List.copyOf(evidence);
    }
}
