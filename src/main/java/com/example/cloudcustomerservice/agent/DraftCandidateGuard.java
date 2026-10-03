package com.example.cloudcustomerservice.agent;

import java.util.regex.Pattern;

/** 有限的显式危险宣称拦截，不是事实核验器；通过此检查仍只允许在实验页审阅，不能进入正式记录。 */
final class DraftCandidateGuard {
    private static final Pattern CLAIM = Pattern.compile(
            "(?:已经|已为您|已为你|已成功|现已|已)(?:完成|帮您|帮你|为您|为你)?(?:提交|受理|批准|退款|退回|打款|转人工|创建申请)"
            + "|(?:退款|退货|申请)(?:已经|已)(?:获批|批准|完成|到账|提交|受理)"
            + "|(?:保证|一定|必定)(?:会)?(?:退款|退货|获批|批准)"
            + "|(?:successfully\\s+)?(?:submitted|approved|refunded)|refund\\s+(?:completed|processed)",
            Pattern.CASE_INSENSITIVE);
    static boolean accepts(String text) {
        return text!=null && !text.isBlank() && text.length()<=12000
                && !CLAIM.matcher(text).find();
    }
}
