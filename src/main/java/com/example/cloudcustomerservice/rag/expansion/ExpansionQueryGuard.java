package com.example.cloudcustomerservice.rag.expansion;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 模型输出的有限保护规则，不是严格问题拆解器或语义等价验证器。
 * 优先丢弃明显捏造数字、丢条件或提前作答的变体；完整原查询仍作为默认兜底。
 */
public final class ExpansionQueryGuard {
    private static final Pattern NUMBERS = Pattern.compile("[A-Za-z]*\\d+(?:[.-]\\d+)*|[零一二三四五六七八九十百两]+(?:个)?(?:工作日|天|日|小时|分钟|月|年)");
    private static final Pattern NEGATIVE = Pattern.compile("未激活|未曾激活|没有激活|尚未激活");
    private static final Pattern POSITIVE = Pattern.compile("已经激活|已激活|激活后");
    private static final Pattern QUALITY = Pattern.compile("质量|损坏|破损|故障|坏了");
    private static final List<Pattern> TOPICS = List.of(
            Pattern.compile("退货条件|退货资格|能.{0,5}退|可.{0,5}退|是否.{0,5}退|售后条件"),
            Pattern.compile("运费|邮费|费用"), Pattern.compile("退款.{0,12}(多久|时间|时效|提交|到账)|多久退款"),
            Pattern.compile("物流|包裹|快递"), Pattern.compile("发票"), Pattern.compile("会员|权益"), Pattern.compile("优惠券"));
    /** 纯静态规则集合。 */
    private ExpansionQueryGuard() { }

    /** 自动策略是可解释的主题计数，单纯出现“退货运费”不会被误计为两个主题。 */
    public static boolean multiTopic(String text) {
        return TOPICS.stream().filter(p -> p.matcher(text).find()).count() >= 2
                || text.matches("(?s).*(售后权益有哪些|退货.*条件和费用|会员售后权益).*");
    }

    /** 去掉常见列表编号，合并重复仅按归一化文本，不做语义去重。 */
    public static String clean(String text) {
        return text == null ? "" : text.strip().replaceFirst("^(?:[-*•]|\\d+[.)、])\\s*", "").strip();
    }

    /** 重要条件保留要求偏保守，误丢弃会在 rejectedVariants 中可见。 */
    public static boolean valid(String original, String candidate) {
        if (candidate.isBlank() || candidate.length() > 2000 || candidate.contains("\n")
                || candidate.contains("```") || candidate.contains("<") || candidate.contains(">")) return false;
        if (!candidate.matches("(?s).*(谁|哪|如何|是否|什么|几|多少|多久|怎么|能否|可否|吗|？|\\?).*")) return false;
        Set<String> facts = tokens(original), output = tokens(candidate);
        // 不仅禁止新增数字，也保留当前查询中的编号和时间，避免拆分时丢掉限定条件。
        if (!facts.equals(output)) return false;
        if (QUALITY.matcher(original).find() && !QUALITY.matcher(candidate).find()) return false;
        if (NEGATIVE.matcher(original).find() && (!NEGATIVE.matcher(candidate).find() || POSITIVE.matcher(candidate).find())) return false;
        if (!NEGATIVE.matcher(original).find() && POSITIVE.matcher(original).find()
                && (!POSITIVE.matcher(candidate).find() || NEGATIVE.matcher(candidate).find())) return false;
        if (original.contains("提交") && !original.contains("到账") && candidate.contains("到账")) return false;
        // 在线发现“退款多久提交”被改成“用户多久内提交退款申请”，执行主体和时间方向都变了。
        // 用户原文未提申请时，禁止在退款提交视角中新增申请期限。
        if (original.contains("退款") && original.contains("提交")
                && !original.matches("(?s).*(退款申请|申请退款|提交申请).*")
                && candidate.matches("(?s).*(提交.{0,16}申请|(?:退款申请|申请退款).{0,24}提交|申请(?:时限|期限|截止)|截止日期).*")) return false;
        return !candidate.contains("无理由") || original.contains("无理由");
    }

    /** 保留字面数量而非猜测同义换算，十天变成七天或 A10001 变成 A10002 都会失败。 */
    private static Set<String> tokens(String value) {
        Set<String> result = new HashSet<>();
        var m = NUMBERS.matcher(value);
        while (m.find()) result.add(m.group());
        return result;
    }
}
