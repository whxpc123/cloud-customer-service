package com.example.cloudcustomerservice.knowledge;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 第十章动态检索过滤器。租户先白名单校验，再插入过滤表达式，不能拼接任意输入。 */
@Component
@Profile("local & knowledge")
public class KnowledgeFilterFactory {
    /**
     * 只允许检索当前租户已发布的中文售后知识，限制与前面章节的搜索服务一致。
     * @param tenantId 服务端已确定的租户标识，1～80 个字母、数字、下划线或短横线
     * @return VectorStoreDocumentRetriever.FILTER_EXPRESSION 所需的表达式文本
     */
    public String publishedAfterSales(String tenantId) {
        if (tenantId == null || !tenantId.matches("[a-zA-Z0-9_-]{1,80}")) {
            throw new IllegalArgumentException("Invalid tenantId");
        }
        return "tenantId == '" + tenantId + "' && status == 'PUBLISHED'"
                + " && knowledgeBase == 'after-sales' && language == 'zh-CN'";
    }
}
