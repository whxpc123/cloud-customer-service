package com.example.cloudcustomerservice.submission;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.state.strategy.*;
import com.example.cloudcustomerservice.aftersale.AfterSaleModel.Actor;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.web.server.ResponseStatusException;
import static com.alibaba.cloud.ai.graph.StateGraph.*;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;

/**
 * 第26章真实提交图：先查回执，未观察到成功才调用幂等服务；没有返回执行节点的重试边。
 * 图本身不持有数据库事务，节点通过注入的 Spring 服务代理开启独立短事务。
 */
@Service @Profile("local & knowledge")
public class SubmissionGraph {
    public record Execution(UUID operationId,String status,String message,List<String> trace,
            IdempotentSubmissionService.Receipt receipt,boolean replayed) { }
    private final IdempotentSubmissionService service;
    public SubmissionGraph(IdempotentSubmissionService service){this.service=service;}

    /** operationId 由受控 HTTP 路径绑定；重跑同一图始终使用同一编号，不产生新操作。 */
    public Execution execute(Actor actor,UUID operationId) {
        try {
            StateGraph graph=new StateGraph("idempotent-local-submission-v1",()->{
                Map<String,KeyStrategy> keys=new HashMap<>();
                for(String key:List.of("route","status","receipt","message","replayed"))keys.put(key,new ReplaceStrategy());
                keys.put("trace",new AppendStrategy());return keys;
            });
            graph.addNode("lookup_receipt",node_async(s->{
                try {
                    var query=service.result(actor,operationId);
                    if(query.receipt()!=null)return Map.of("route","FOUND","receipt",query.receipt(),"replayed",true,"trace",List.of("lookup_receipt"));
                    return Map.of("route","MISSING","trace",List.of("lookup_receipt"));
                }catch(DataAccessException|TransactionException e){return uncertain("lookup_receipt");}
            }));
            graph.addNode("submit_once",node_async(s->{
                try{return Map.of("route","CREATED","receipt",service.submit(actor,operationId),"trace",List.of("submit_once"));}
                catch(ResponseStatusException e){
                    if(e.getStatusCode().value()!=409)throw e;
                    return Map.of("route","BLOCKED","message",Objects.requireNonNullElse(e.getReason(),"首次执行条件不满足"),"trace",List.of("submit_once"));
                }catch(DataAccessException|TransactionException e){return uncertain("submit_once");}
            }));
            graph.addNode("receipt",node_async(s->Map.of("status","APPLICATION_CREATED_PENDING_REVIEW","trace",List.of("receipt"))));
            graph.addNode("blocked",node_async(s->Map.of("status","BLOCKED","trace",List.of("blocked"))));
            graph.addNode("reconcile",node_async(s->Map.of("status","RECONCILIATION_REQUIRED","trace",List.of("reconcile"))));
            graph.addEdge(START,"lookup_receipt");
            graph.addConditionalEdges("lookup_receipt",edge_async(s->s.<String>value("route").orElseThrow()),Map.of("FOUND","receipt","MISSING","submit_once","UNKNOWN","reconcile"));
            graph.addConditionalEdges("submit_once",edge_async(s->s.<String>value("route").orElseThrow()),Map.of("CREATED","receipt","BLOCKED","blocked","UNKNOWN","reconcile"));
            for(String node:List.of("receipt","blocked","reconcile"))graph.addEdge(node,END);
            var state=graph.compile(CompileConfig.builder().recursionLimit(12).build()).invoke(Map.of("trace",List.of()),
                RunnableConfig.builder().threadId("local-submit:"+UUID.randomUUID()).build()).orElseThrow();
            return new Execution(operationId,state.<String>value("status").orElseThrow(),
                state.<String>value("message").orElse("本系统申请已创建，等待审核；没有执行退款"),
                List.copyOf(state.<List<String>>value("trace").orElseThrow()),state.<IdempotentSubmissionService.Receipt>value("receipt").orElse(null),
                state.<Boolean>value("replayed").orElse(false));
        }catch(Exception e){
            // 框架包装异常不改变 HTTP 授权结果；不把跨用户 404 包装成可查询的业务核查结果。
            for(Throwable cause=e;cause!=null;cause=cause.getCause())if(cause instanceof ResponseStatusException denied)throw denied;
            throw new IllegalStateException("提交图未正常结束，请使用原操作编号核查结果",e);
        }
    }
    /** 数据库异常发生在提交前或确认提交时均有可能；不猜测失败、不自动重试。 */
    private static Map<String,Object> uncertain(String node){return Map.of("route","UNKNOWN","message","尚未确认执行结果，请用原操作编号查询；未自动重试","trace",List.of(node));}
}
