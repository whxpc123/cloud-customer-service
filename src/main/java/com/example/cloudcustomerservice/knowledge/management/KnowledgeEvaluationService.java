package com.example.cloudcustomerservice.knowledge.management;

import com.example.cloudcustomerservice.knowledge.LocalKnowledgeDocuments;
import com.example.cloudcustomerservice.rag.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 本地单实例基础评测：有界队列逐题执行真实问答，把输入快照及增量结果保存到 PostgreSQL。
 * 不使用模型自评冒充正确率；只测期望来源是否被召回、无证据拦截是否符合题目和整轮耗时。
 */
@Service
@Profile("local & knowledge")
public class KnowledgeEvaluationService {
    /** 每题可指定期望来源集合，命中其中至少一个即 sourceHit；拒答期望需显式填写。 */
    public record Case(String question, boolean shouldRefuse, List<String> expectedSources) { }
    /** 前端输入允许 null 布尔，由校验拒绝缺失字段；名称作为这次题集快照标题。 */
    public record CaseInput(String question, Boolean shouldRefuse, List<String> expectedSources) { }
    /** 一次最多 20 题，不在请求线程中等待模型。 */
    public record Request(String name, List<CaseInput> cases) { }
    /** unavailable 或异常不算正确拒答，比较字段为 null，失败单独计数。 */
    public record Result(int index, Case testCase, AdvisorKnowledgeAnswerResponse response, long durationMs,
            Boolean refusalMatched, Boolean sourceHit, String error) { }
    /** 可持久读取的运行快照，刷新页面及重启后仍能查到已保存结果。 */
    public record Run(String id,String name,String status,String createdAt,String finishedAt,List<Case> cases,List<Result> results) { }
    /** 列表避免返回全部知识正文，结果数量表示已保存题数。 */
    public record Summary(String id,String name,String status,String createdAt,int cases,int completed) { }
    private static final String TENANT=LocalKnowledgeDocuments.TENANT_ID;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AdvisorKnowledgeAnswerService answers;
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),r->{
        Thread t=new Thread(r,"knowledge-evaluation");t.setDaemon(true);return t;
    },new ThreadPoolExecutor.AbortPolicy());
    /** 注入真实 Advisor 服务；测试可替换它，SQL 持久化仍使用真实专用测试库。 */
    public KnowledgeEvaluationService(JdbcTemplate jdbc,ObjectMapper json,AdvisorKnowledgeAnswerService answers) {
        this.jdbc=jdbc;this.json=json;this.answers=answers;
    }
    /** 进程重启不擅自重发收费模型调用，遗留未完成运行标为 INTERRUPTED，保留已完成题目。 */
    @PostConstruct public void recover() {
        jdbc.update("UPDATE ai.knowledge_evaluation_runs SET status='INTERRUPTED',finished_at=now() WHERE tenant_id=? AND status IN ('QUEUED','RUNNING')",TENANT);
    }
    /** 验证题集并持久化输入快照，再提交有界后台任务。 */
    public synchronized Run create(Request request) {
        List<Case> cases=validate(request);
        if(executor.getQueue().remainingCapacity()==0)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"评测队列已满，请等待当前运行结束");
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai.knowledge_evaluation_runs(id,tenant_id,name,status,cases) VALUES (?::uuid,?,?,'QUEUED',?::jsonb)",id,TENANT,request.name().strip(),encode(cases));
        try { executor.execute(()->execute(id,cases)); }
        catch(RejectedExecutionException ex) {
            jdbc.update("UPDATE ai.knowledge_evaluation_runs SET status='INTERRUPTED',finished_at=now() WHERE id=?::uuid AND tenant_id=?",id,TENANT);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"评测服务正在停止，请稍后重试");
        }
        return get(id);
    }
    /** 校验完整题集后才能写库和调用模型；来源 ID 不是文件路径，也不是任意过滤表达式。 */
    public static List<Case> validate(Request request) {
        if(request==null || request.name()==null || request.name().isBlank() || request.name().length()>80
                || request.cases()==null || request.cases().isEmpty() || request.cases().size()>20)
            throw new IllegalArgumentException("评测名称需 1–80 字符，题集需 1–20 题");
        return request.cases().stream().map(c->{
            if(c==null || c.question()==null || c.question().isBlank() || c.question().length()>2000 || c.shouldRefuse()==null
                    || c.expectedSources()==null || c.expectedSources().size()>20
                    || c.expectedSources().stream().anyMatch(s->s==null || !s.matches("[a-zA-Z0-9_-]{1,160}")))
                throw new IllegalArgumentException("每题需有效问题、明确的 shouldRefuse 和 expectedSources 数组");
            return new Case(c.question().strip(),c.shouldRefuse(),List.copyOf(c.expectedSources()));
        }).toList();
    }
    /** 每题使用全新会话并最终清空，评测不会读取或改写浏览器中的客户会话。 */
    private void execute(String id,List<Case> cases) {
        var results=new ArrayList<Result>();
        try {
            jdbc.update("UPDATE ai.knowledge_evaluation_runs SET status='RUNNING' WHERE id=?::uuid AND tenant_id=?",id,TENANT);
            for(int i=0;i<cases.size();i++) {
                Case test=cases.get(i);String conversation="eval-"+UUID.randomUUID();long start=System.nanoTime();
                AdvisorKnowledgeAnswerResponse response=null;String error=null;
                try {
                    response=answers.answer(TENANT,conversation,test.question());
                    if(response==null || response.status()==KnowledgeAnswerStatus.TEMPORARILY_UNAVAILABLE)error="问答服务暂不可用";
                } catch(RuntimeException ex) {error="本题执行失败，请检查服务日志";}
                finally {answers.clearMemory(TENANT,conversation,null);}
                long duration=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
                results.add(compare(i+1,test,response,duration,error));
                jdbc.update("UPDATE ai.knowledge_evaluation_runs SET results=?::jsonb WHERE id=?::uuid AND tenant_id=?",encode(results),id,TENANT);
            }
            String status=results.stream().anyMatch(r->r.error()!=null)?"COMPLETED_WITH_ERRORS":"COMPLETED";
            jdbc.update("UPDATE ai.knowledge_evaluation_runs SET status=?,finished_at=now() WHERE id=?::uuid AND tenant_id=?",status,id,TENANT);
        } catch(RuntimeException ex) {
            // 持久化失败时不能报告完成；尽力记录中断，数据库仍不可用时下一次启动 recover 处理。
            try {jdbc.update("UPDATE ai.knowledge_evaluation_runs SET status='INTERRUPTED',finished_at=now() WHERE id=?::uuid AND tenant_id=?",id,TENANT);}
            catch(RuntimeException ignored) { /* 不暴露 SQL、正文或凭证。 */ }
        }
    }
    /** 基于接口状态和真实来源的确定性比较；不是自然语言正确性或逐句引用的判定。 */
    public static Result compare(int index,Case test,AdvisorKnowledgeAnswerResponse response,long duration,String error) {
        if(error!=null || response==null || response.status()==KnowledgeAnswerStatus.TEMPORARILY_UNAVAILABLE)
            return new Result(index,test,response,duration,null,null,error==null?"问答服务暂不可用":error);
        if (response.status()==KnowledgeAnswerStatus.NEEDS_CLARIFICATION)
            return new Result(index,test,response,duration,null,null,"问题需要补充场景，本题未进入知识检索");
        boolean refusal=response.status()==KnowledgeAnswerStatus.NO_EVIDENCE;
        Boolean hit=test.expectedSources().isEmpty()?null:response.references().stream().anyMatch(r->test.expectedSources().contains(r.sourceId()));
        return new Result(index,test,response,duration,refusal==test.shouldRefuse(),hit,null);
    }
    /** 最近 50 次运行摘要；更旧记录仍可通过已知 ID 打开，不把列表伪装为全库统计。 */
    public List<Summary> list() {
        return jdbc.query("SELECT id,name,status,created_at,jsonb_array_length(cases),jsonb_array_length(results) FROM ai.knowledge_evaluation_runs WHERE tenant_id=? ORDER BY created_at DESC LIMIT 50",
                (rs,row)->new Summary(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5),rs.getInt(6)),TENANT);
    }
    /** 租户范围内读取输入与实际结果；运行中的结果是一致的单条数据库快照。 */
    public Run get(String id) {
        try {UUID.fromString(id);} catch(RuntimeException ex) {throw new IllegalArgumentException("评测 ID 不合法");}
        return jdbc.query("SELECT * FROM ai.knowledge_evaluation_runs WHERE id=?::uuid AND tenant_id=?",(rs,row)->{
            try { return new Run(rs.getString("id"),rs.getString("name"),rs.getString("status"),rs.getString("created_at"),rs.getString("finished_at"),
                    json.readValue(rs.getString("cases"),new TypeReference<List<Case>>(){}),json.readValue(rs.getString("results"),new TypeReference<List<Result>>(){})); }
            catch(java.io.IOException ex) {throw new IllegalStateException("Invalid stored evaluation",ex);}
        },id,TENANT).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"评测不存在"));
    }
    /** JSON 由序列化器生成，通过 SQL 参数保存，避免内容改变 SQL 结构。 */
    private String encode(Object value) {
        try {return json.writeValueAsString(value);} catch(com.fasterxml.jackson.core.JsonProcessingException ex) {throw new IllegalStateException("Cannot encode evaluation",ex);}
    }
    /** 不再接收新运行，正在退出的进程遗留任务由下一次启动标记为中断。 */
    @PreDestroy public void close() {executor.shutdown();}
}
