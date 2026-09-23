package com.example.cloudcustomerservice.aftersale;

import java.util.*;
import java.util.regex.Pattern;
import static com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;

/** 有限输出防错：拦截验收已观察到的状态、日期和流程幻觉，不宣称完成逐句事实验证。 */
public final class AfterSaleExplanationGuard {
    private static final Pattern APPROVAL=Pattern.compile("已(?:经)?(?:批准|退款|执行退款|提交.{0,8}(?:申请|退款))|退款(?:已到账|成功|已执行)|可以直接退款|已为.{0,12}(?:办理|退款)");
    private static final Pattern DATE=Pattern.compile("20[0-9]{2}-[0-9]{2}-[0-9]{2}");
    private static final Pattern TIME=Pattern.compile("[0-9]{2}:[0-9]{2}:[0-9]{2}");
    private AfterSaleExplanationGuard() { }
    public static boolean shouldReplace(String answer,List<Assessment> assessments){
        if(answer==null||answer.isBlank()||APPROVAL.matcher(answer).find())return true;
        // 无权限、无适用证据或服务失败一律用 Java 原结论，避免模型推断不存在的订单事实和自动重试流程。
        if(assessments.stream().anyMatch(a->Set.of(AssessmentStatus.NOT_ACCESSIBLE,AssessmentStatus.NO_EVIDENCE,AssessmentStatus.TEMPORARILY_UNAVAILABLE).contains(a.status())))return true;
        var statuses=new HashSet<String>();var dates=new HashSet<String>();var times=new HashSet<String>();var supported=new StringBuilder();
        for(var a:assessments){statuses.add(a.status().name());supported.append(a.explanation());a.evidence().forEach(e->supported.append(e.text()));
            if(a.verifiedFacts()!=null){var f=a.verifiedFacts();for(var time:new java.time.OffsetDateTime[]{f.signedAt(),f.noReasonDeadline()})if(time!=null){dates.add(time.toLocalDate().toString());times.add(time.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));}}}
        for(var status:AssessmentStatus.values())if(answer.contains(status.name())&&!statuses.contains(status.name()))return true;
        var date=DATE.matcher(answer);while(date.find())if(!dates.contains(date.group()))return true;
        var time=TIME.matcher(answer);while(time.find())if(!times.contains(time.group()))return true;
        for(String step:List.of("拍照","照片","视频","寄回","自动重试","自动触发","等待联系","人员联系","上传","页面提示","凭证","触发退款"))
            if(answer.contains(step)&&supported.indexOf(step)<0)return true;
        return false;
    }
}
