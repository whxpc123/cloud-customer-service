package com.example.cloudcustomerservice.handoff;

import com.example.cloudcustomerservice.routing.*;
import com.example.cloudcustomerservice.security.HandoffIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.example.cloudcustomerservice.handoff.HandoffModel.*;

/**
 * 会话状态与正式消息的事务边界。所有状态变更和答案发布锁同一行，会话行是唯一接待状态来源。
 * 此 Bean 的公开方法由控制器/编排器通过 Spring 代理调用；事务中不调用模型、向量检索或外部服务。
 */
@Service
@Profile("local & knowledge")
@PreAuthorize("isAuthenticated()")
public class HumanHandoffService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public HumanHandoffService(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    private record Row(UUID id,long userId,Mode mode,UUID handoffId,Long agentId,long version,
            OffsetDateTime requestedAt,OffsetDateTime acceptedAt,OffsetDateTime closedAt,UUID generationId,boolean generationFresh,long memoryAfter){ }
    private static final String SELECT="SELECT *, generation_started_at > CURRENT_TIMESTAMP - INTERVAL '5 minutes' AS generation_fresh FROM ai.cs_conversation";
    private final RowMapper<Row> rows=(r,n)->new Row(r.getObject("id",UUID.class),r.getLong("user_id"),Mode.valueOf(r.getString("mode")),
            r.getObject("handoff_id",UUID.class),r.getObject("assigned_agent_id",Long.class),r.getLong("version"),
            r.getObject("requested_at",OffsetDateTime.class),r.getObject("accepted_at",OffsetDateTime.class),r.getObject("closed_at",OffsetDateTime.class),
            r.getObject("generation_id",UUID.class),r.getBoolean("generation_fresh"),r.getLong("memory_after"));

    @Transactional(timeout=5) @PreAuthorize("hasAuthority('customer:chat')")
    public Receipt createConversation(Actor actor){
        HandoffIdentity.requireSame(actor);UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO ai.cs_conversation(tenant_id,id,user_id) VALUES(?,?,?)",actor.tenantId(),id,actor.accountId());
        return receipt(load(actor,id,true,false));
    }
    @Transactional(readOnly=true)
    public Receipt get(Actor actor,UUID id){HandoffIdentity.requireSame(actor);return receipt(load(actor,id,true,false));}
    /** 刷新或重新登录后按真实账户恢复会话，不再依赖上章的 Session UUID 注册表。 */
    @Transactional(readOnly=true) @PreAuthorize("hasAuthority('customer:chat')")
    public List<Receipt> conversations(Actor actor){HandoffIdentity.requireSame(actor);return jdbc.query(SELECT+" WHERE tenant_id=? AND user_id=? ORDER BY created_at DESC,id LIMIT 50",rows,actor.tenantId(),actor.accountId()).stream().map(this::receipt).toList();}
    /** 重复申请、提交后丢失响应再重试都返回同一个 handoffId，CLOSED 不重新排队。 */
    @Transactional(timeout=5,rollbackFor=Exception.class) @PreAuthorize("hasAuthority('customer:chat')")
    public Receipt request(Actor actor,UUID id){HandoffIdentity.requireSame(actor);return requestLocked(actor,load(actor,id,true,true));}
    private Receipt requestLocked(Actor actor,Row row){
        if(row.mode()==Mode.BOT){
            jdbc.update("UPDATE ai.cs_conversation SET mode='WAITING_HUMAN',handoff_id=?,requested_at=CURRENT_TIMESTAMP,version=version+1,generation_id=NULL,generation_started_at=NULL WHERE tenant_id=? AND id=?",UUID.randomUUID(),actor.tenantId(),row.id());
            var updated=load(actor,row.id(),true,false);var r=receipt(updated);
            insert(actor,row.id(),"SYSTEM",null,null,null,r.message(),null,r.version());return r;
        }
        return receipt(row);
    }
    @Transactional(readOnly=true) @PreAuthorize("hasAuthority('support:serve')")
    public List<Receipt> waiting(Actor agent){HandoffIdentity.requireSame(agent);return jdbc.query(SELECT+" WHERE tenant_id=? AND mode='WAITING_HUMAN' ORDER BY requested_at,id LIMIT 50",rows,agent.tenantId()).stream().map(this::receipt).toList();}
    /** 两位客服领取同一行时串行检查；只有一个能从 WAITING_HUMAN 变成 HUMAN_ACTIVE。 */
    @Transactional(timeout=5,rollbackFor=Exception.class) @PreAuthorize("hasAuthority('support:serve')")
    public Receipt accept(Actor agent,UUID id){
        HandoffIdentity.requireSame(agent);Row row=load(agent,id,false,true);
        if(row.mode()==Mode.HUMAN_ACTIVE&&Objects.equals(row.agentId(),agent.accountId()))return receipt(row);
        if(row.mode()!=Mode.WAITING_HUMAN)throw conflict("当前不能领取，请刷新队列");
        jdbc.update("UPDATE ai.cs_conversation SET mode='HUMAN_ACTIVE',assigned_agent_id=?,accepted_at=CURRENT_TIMESTAMP,version=version+1 WHERE tenant_id=? AND id=?",agent.accountId(),agent.tenantId(),id);
        var r=receipt(load(agent,id,false,false));insert(agent,id,"SYSTEM",agent.accountId(),null,null,r.message(),null,r.version());return r;
    }
    @Transactional(timeout=5,rollbackFor=Exception.class) @PreAuthorize("hasAuthority('support:serve')")
    public Receipt close(Actor agent,UUID id){
        HandoffIdentity.requireSame(agent);Row row=load(agent,id,false,true);
        if(!Objects.equals(row.agentId(),agent.accountId()))throw conflict("只有当前领取客服可以结束会话");
        if(row.mode()==Mode.CLOSED)return receipt(row);
        if(row.mode()!=Mode.HUMAN_ACTIVE)throw conflict("当前不处于人工接待状态");
        jdbc.update("UPDATE ai.cs_conversation SET mode='CLOSED',closed_at=CURRENT_TIMESTAMP,version=version+1 WHERE tenant_id=? AND id=?",agent.tenantId(),id);
        var r=receipt(load(agent,id,false,false));insert(agent,id,"SYSTEM",agent.accountId(),null,null,r.message(),null,r.version());return r;
    }

    /**
     * 先落用户原文。等待/接待中只保存消息；机器人状态领取五分钟生成租约，并返回有限历史快照。
     * 同一 clientMessageId 重试不重复追加；换正文复用 ID 返回冲突。模型工作期间数据库锁已经释放。
     */
    @Transactional(timeout=5,rollbackFor=Exception.class) @PreAuthorize("hasAuthority('customer:chat')")
    public Turn begin(Actor actor,UUID id,UUID clientMessageId,String text,boolean explicitHuman){
        HandoffIdentity.requireSame(actor);new CustomerRouter.Input(text,"");Objects.requireNonNull(clientMessageId);
        Row row=load(actor,id,true,true);
        var duplicate=jdbc.queryForList("SELECT id,content FROM ai.cs_message WHERE tenant_id=? AND conversation_id=? AND client_message_id=?",actor.tenantId(),id,clientMessageId);
        if(!duplicate.isEmpty()){
            if(!text.equals(duplicate.get(0).get("content")))throw conflict("相同消息编号不能用于不同正文");
            return new Turn(receipt(row),null,((Number)duplicate.get(0).get("id")).longValue(),true,List.of());
        }
        if(row.mode()==Mode.CLOSED)throw conflict("会话已结束，请新建会话");
        if(!explicitHuman&&row.mode()==Mode.BOT&&row.generationId()!=null&&row.generationFresh())throw conflict("上一条机器人消息仍在处理；可以直接申请人工，或等待处理完成");
        Message user=insert(actor,id,"USER",actor.accountId(),clientMessageId,null,text,null,row.version());
        if(explicitHuman)return new Turn(requestLocked(actor,row),null,user.id(),false,List.of());
        if(row.mode()!=Mode.BOT)return new Turn(receipt(row),null,user.id(),false,List.of());
        UUID generation=UUID.randomUUID();
        jdbc.update("UPDATE ai.cs_conversation SET generation_id=?,generation_started_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",generation,actor.tenantId(),id);
        var history=jdbc.query("SELECT role,content FROM ai.cs_message WHERE tenant_id=? AND conversation_id=? AND id>? AND id<? AND role IN ('USER','BOT') ORDER BY id DESC LIMIT 20",
                (r,n)->(org.springframework.ai.chat.messages.Message)("USER".equals(r.getString(1))?new org.springframework.ai.chat.messages.UserMessage(r.getString(2)):new org.springframework.ai.chat.messages.AssistantMessage(r.getString(2))),actor.tenantId(),id,row.memoryAfter(),user.id());
        Collections.reverse(history);
        return new Turn(receipt(row),generation,user.id(),false,List.copyOf(history));
    }

    /** 正式发布门：同一短事务中锁会话、复核 mode/version/租约并保存消息，不能先检查再另行保存。 */
    @Transactional(timeout=5,rollbackFor=Exception.class) @PreAuthorize("hasAuthority('customer:chat')")
    public Delivery finish(Actor actor,UUID id,Turn turn,RoutedCustomerService.Response candidate){
        HandoffIdentity.requireSame(actor);Row row=load(actor,id,true,true);
        if(row.mode()!=Mode.BOT||row.version()!=turn.receipt().version()||!Objects.equals(row.generationId(),turn.generationId())||turn.generationId()==null)
            return new Delivery(receipt(row),false,"STALE_DISCARDED",null);
        if(candidate.decision().route()==CustomerRouter.Route.HUMAN_SERVICE){
            Receipt r=requestLocked(actor,row);return new Delivery(r,false,"HANDOFF_ACCEPTED",null);
        }
        Message published=insert(actor,id,"BOT",null,null,turn.userMessageId(),candidate.answer(),json.valueToTree(candidate),row.version());
        jdbc.update("UPDATE ai.cs_conversation SET generation_id=NULL,generation_started_at=NULL WHERE tenant_id=? AND id=?",actor.tenantId(),id);
        return new Delivery(receipt(row),true,"ROBOT_PUBLISHED",published);
    }

    /** 重置模型窗口不删除正式记录，不能恢复机器人接待或取消人工申请。 */
    @Transactional(timeout=5) @PreAuthorize("hasAuthority('customer:chat')")
    public Receipt resetMemory(Actor actor,UUID id){
        HandoffIdentity.requireSame(actor);Row row=load(actor,id,true,true);
        if(row.mode()!=Mode.BOT||row.generationId()!=null&&row.generationFresh())throw conflict("仅能在机器人空闲时重置上下文");
        jdbc.update("UPDATE ai.cs_conversation SET memory_after=(SELECT COALESCE(MAX(id),0) FROM ai.cs_message WHERE tenant_id=? AND conversation_id=?),version=version+1,generation_id=NULL,generation_started_at=NULL WHERE tenant_id=? AND id=?",actor.tenantId(),id,actor.tenantId(),id);
        var r=receipt(load(actor,id,true,false));insert(actor,id,"SYSTEM",null,null,null,"模型上下文已重置；正式聊天记录仍然保留。",null,r.version());return r;
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public History history(Actor actor,UUID id,long after){HandoffIdentity.requireSame(actor);return historyLocked(actor,load(actor,id,true,false),after);}
    /** 客服可看待领取会话，领取后仅当前客服可读；跨租户、不属于自己的已领取记录不泄露。 */
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ) @PreAuthorize("hasAuthority('support:serve')")
    public History supportHistory(Actor agent,UUID id,long after){
        HandoffIdentity.requireSame(agent);Row row=load(agent,id,false,false);
        if(row.mode()!=Mode.WAITING_HUMAN&&!Objects.equals(row.agentId(),agent.accountId()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"未找到可访问的人工会话");
        return historyLocked(agent,row,after);
    }
    private History historyLocked(Actor actor,Row row,long after){
        if(after<0)throw new IllegalArgumentException("消息游标不能为负数");
        var messages=jdbc.query("SELECT * FROM ai.cs_message WHERE tenant_id=? AND conversation_id=? AND id>? ORDER BY id LIMIT 101",this::message,actor.tenantId(),row.id(),after);
        boolean more=messages.size()>100;var page=List.copyOf(messages.subList(0,Math.min(100,messages.size())));
        return new History(receipt(row),page,page.isEmpty()?after:page.get(page.size()-1).id(),more);
    }
    private Row load(Actor actor,UUID id,boolean owner,boolean lock){
        String sql=SELECT+" WHERE tenant_id=? AND id=?"+(owner?" AND user_id=?":"")+(lock?" FOR UPDATE":"");
        var found=jdbc.query(sql,rows,owner?new Object[]{actor.tenantId(),id,actor.accountId()}:new Object[]{actor.tenantId(),id});
        if(found.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"未找到可访问的会话");return found.get(0);
    }
    private Receipt receipt(Row row){
        String text=switch(row.mode()){
            case BOT->"当前由智能客服处理。";
            case WAITING_HUMAN->"人工申请已受理，正在等待客服领取。当前不提供预计等待时间。";
            case HUMAN_ACTIVE->"已有客服领取本次会话。本章仅提供接待状态与记录查询，尚未接入真人消息通道。";
            case CLOSED->"本次会话已结束；如需继续咨询，请新建会话。";
        };
        return new Receipt(row.id(),row.handoffId(),row.mode(),row.version(),row.agentId(),row.requestedAt(),row.acceptedAt(),row.closedAt(),text);
    }
    private Message insert(Actor actor,UUID id,String role,Long author,UUID clientId,Long replyTo,String text,com.fasterxml.jackson.databind.JsonNode payload,long version){
        return jdbc.queryForObject("INSERT INTO ai.cs_message(tenant_id,conversation_id,role,author_id,client_message_id,reply_to,content,payload,version) VALUES(?,?,?,?,?,?,?,?::jsonb,?) RETURNING *",
                this::message,actor.tenantId(),id,role,author,clientId,replyTo,text,payload==null?null:payload.toString(),version);
    }
    private Message message(java.sql.ResultSet r,int index)throws java.sql.SQLException{
        try{return new Message(r.getLong("id"),r.getString("role"),r.getString("content"),r.getString("payload")==null?null:json.readTree(r.getString("payload")),r.getLong("version"),r.getObject("created_at",OffsetDateTime.class));}
        catch(java.io.IOException ex){throw new IllegalStateException("已保存消息格式异常");}
    }
    private ResponseStatusException conflict(String text){return new ResponseStatusException(HttpStatus.CONFLICT,text);}
}
