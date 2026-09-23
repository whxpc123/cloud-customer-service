package com.example.cloudcustomerservice.rag.rerank;

import java.util.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/** 精排以后按完整块选择，超长块跳过并说明原因；不剪掉制度中的条件或例外。 */
public final class ContextBudgetDocumentPostProcessor implements DocumentPostProcessor {
    private final TokenCountEstimator estimator;
    public ContextBudgetDocumentPostProcessor(TokenCountEstimator estimator) { this.estimator=estimator; }
    @Override public List<Document> process(Query query,List<Document> docs) {
        var trace=QwenRerankDocumentPostProcessor.trace(query);var selected=new ArrayList<Document>();int used=0;
        for(Document d:docs) {
            if(selected.size()>=6) { trace.exclude(d,"CONTEXT","MAX_DOCUMENTS");continue; }
            if(d.getText()==null || d.getText().isBlank()) { trace.exclude(d,"CONTEXT","EMPTY_TEXT");continue; }
            String combined=String.join("\n\n",selected.stream().map(Document::getText).toList());
            int tokens=estimator.estimate(combined+(selected.isEmpty()?"":"\n\n")+d.getText());
            if(tokens>trace.options().maxContextTokens()) {trace.exclude(d,"CONTEXT","TOKEN_BUDGET");continue;}
            selected.add(d);used=tokens;
        }
        trace.budget(selected,used);return List.copyOf(selected);
    }
}
