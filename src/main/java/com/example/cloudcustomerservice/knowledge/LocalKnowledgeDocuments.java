package com.example.cloudcustomerservice.knowledge;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local & knowledge")
public class LocalKnowledgeDocuments {
    public static final String TENANT_ID = "tenant-yunshan";
    public List<Document> documents() {
        return List.of(
            document("refund-policy", "3.2", 1, "REFUND_POLICY", "除特殊商品外，消费者自签收商品次日起七日内，在商品保持完好且不影响二次销售的情况下，可以申请无理由退货。"),
            document("refund-policy", "3.2", 2, "REFUND_FREIGHT", "因商品质量问题产生的退货运费，由云杉商城承担；因消费者个人原因申请无理由退货的，退货运费原则上由消费者承担。"),
            document("logistics-policy", "2.1", 1, "LOGISTICS_EXCEPTION", "物流信息超过四十八小时没有更新时，用户可以联系在线客服申请物流核查。核查期间客服不得擅自承诺包裹已经丢失。"),
            document("invoice-policy", "1.4", 1, "INVOICE_POLICY", "订单完成后，用户可以在订单详情页申请电子发票。电子发票开具完成后，将发送至用户绑定的邮箱。")
        );
    }
    private Document document(String source, String version, int chunk, String category, String text) {
        String id = UUID.nameUUIDFromBytes((TENANT_ID + "|" + source + "|" + version + "|" + chunk)
                .getBytes(StandardCharsets.UTF_8)).toString();
        return new Document(id, text, Map.ofEntries(
                Map.entry("tenantId", TENANT_ID), Map.entry("knowledgeBase", "after-sales"),
                Map.entry("sourceId", source), Map.entry("sourceVersion", version), Map.entry("chunkIndex", chunk),
                Map.entry("category", category), Map.entry("status", "PUBLISHED"), Map.entry("language", "zh-CN"),
                Map.entry("embeddingModel", "text-embedding-v4"), Map.entry("embeddingDimensions", 1024),
                Map.entry("embeddingProfileVersion", "1"), Map.entry("chunkingVersion", "manual-1")));
    }
}
