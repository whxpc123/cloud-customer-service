package com.example.cloudcustomerservice.intent;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 第三章结构化输出契约，也是 BeanOutputConverter 生成 JSON Schema 的依据。
 * required 标记描述格式要求；模型结果还必须经过识别器的范围和原文来源校验。
 *
 * @param intent 有限枚举中的业务意图，无法可靠识别时为 UNKNOWN
 * @param orderNo 来自客户当前或历史原文的订单号，缺失时为 null；未证明订单归属
 * @param confidence 模型自评的 0～1 置信程度，不是统计正确率
 * @param missingFields 待补充的字段名列表，目前只允许 orderNo
 */
public record IntentRecognitionResult(
        @JsonProperty(required = true) CustomerIntent intent,
        String orderNo,
        @JsonProperty(required = true) double confidence,
        List<String> missingFields
) {
    /**
     * 将未返回的缺失字段归一为空列表，并复制列表，避免外部修改已生成的结构化结果。
     */
    public IntentRecognitionResult {
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
    }

    /**
     * 构造所有识别失败共享的稳定兜底：UNKNOWN、无订单号、置信度 0。
     */
    public static IntentRecognitionResult fallback() {
        return new IntentRecognitionResult(CustomerIntent.UNKNOWN, null, 0.0, List.of());
    }
}
