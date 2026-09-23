package com.example.cloudcustomerservice.knowledge.search;

import java.util.*;
import java.util.regex.Pattern;

/** 确定性识别知识编码；ASCII 边界允许紧邻中文，禁止从更长型号截出半个编码。 */
public final class BusinessIdentifierExtractor {
    private static final Pattern CODE = Pattern.compile("(?<![A-Z0-9_.-])(?:CPN-[A-Z0-9]{4,16}|SKU-[A-Z0-9]{4,16}|POLICY-[0-9]+(?:\\.[0-9]+)*)(?![A-Z0-9_.-])", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORDER = Pattern.compile("(?<![A-Za-z0-9])A[0-9]{5,20}(?![A-Za-z0-9])");
    private BusinessIdentifierExtractor() { }
    /** 最多八个不同编码；超量明确拒绝，不静默遗漏后半段问题。 */
    public static List<String> extract(String text) {
        validate(text); var codes = new LinkedHashSet<String>(); var matcher = CODE.matcher(text);
        while (matcher.find()) { codes.add(matcher.group().toUpperCase(Locale.ROOT)); if (codes.size()>8) throw new IllegalArgumentException("一次最多查询 8 个编码"); }
        return List.copyOf(codes);
    }
    /** 本地演示订单编号及明确的实时状态问题交给业务客服；此规则不是通用意图分类器。 */
    public static boolean requiresTool(String text) {
        return ORDER.matcher(text).find() || text.matches("(?s).*(我的|这笔|这个订单|该订单).*(到账了吗|发货了吗|物流到哪|支付成功了吗|订单状态).*" );
    }
    public static void validate(String query) {
        if(query==null || query.isBlank() || query.length()>2000) throw new IllegalArgumentException("问题须为 1～2000 字符");
    }
}
