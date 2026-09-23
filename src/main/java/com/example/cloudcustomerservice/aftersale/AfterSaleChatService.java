package com.example.cloudcustomerservice.aftersale;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import com.example.cloudcustomerservice.rag.query.KnowledgeConversationLocks;
import static com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;

/** 只接受控制器已验证归属的内部会话键；每轮工具独立，返回模型解释与真实证据包两个部分。 */
@Service
@Profile("local & knowledge")
public class AfterSaleChatService {
    private static final Pattern ORDER=Pattern.compile("(?<![A-Za-z0-9])A[0-9]{5}(?![A-Za-z0-9])",Pattern.CASE_INSENSITIVE);
    private final ChatClient client;private final ChatMemory memory;private final ReturnAssessmentService service;
    public AfterSaleChatService(@Qualifier("afterSaleChatClient") ChatClient client,@Qualifier("customerChatMemory") ChatMemory memory,ReturnAssessmentService service){this.client=client;this.memory=memory;this.service=service;}
    public record Result(String requestId,String status,String answer,List<Assessment> assessments,boolean explanationFiltered,String dataMode){
        public Result {assessments=List.copyOf(assessments);}
    }
    /** 同一会话发送和清空共用锁；无工具结果时覆盖未经核实的模型结论。 */
    public Result answer(Actor actor,String key,String message){
        if(message==null||message.isBlank()||message.length()>4000)throw new IllegalArgumentException("问题须为 1～4000 字符");
        synchronized(KnowledgeConversationLocks.forKey(key)){
            String requestId=UUID.randomUUID().toString();var history=List.copyOf(memory.get(key));
            Set<String> explicit=orders(message),allowed=new LinkedHashSet<>(explicit);
            if(allowed.isEmpty())history.stream().filter(m->m.getMessageType()==MessageType.USER).forEach(m->allowed.addAll(orders(m.getText())));
            // 当前问题没有编号，而历史出现多笔订单时先澄清，不把选择哪笔交给模型猜。
            if(allowed.isEmpty() || explicit.isEmpty()&&allowed.size()>1){
                String answer=allowed.isEmpty()?"请提供明确的订单号和退货原因。本轮尚未完成订单与政策检查。":"历史中有多笔订单，请明确这次要检查哪个订单。本轮尚未执行检查。";
                save(key,history,message,answer);return new Result(requestId,"NOT_CHECKED",answer,List.of(),false,"LOCAL_FIXTURE");
            }
            var tools=new AfterSaleTools(service);String answer=null;boolean failed=false;
            try {answer=client.prompt().user(message).advisors(a->a.param(ChatMemory.CONVERSATION_ID,key))
                    .tools(tools).toolContext(Map.of("actor",actor,"allowedOrders",Set.copyOf(allowed),"requestId",requestId)).call().content();}
            catch(RuntimeException ex){failed=true;org.slf4j.LoggerFactory.getLogger(AfterSaleChatService.class).warn("[AFTER SALE CHAT] requestId={} errorType={}",requestId,ex.getClass().getSimpleName());}
            var assessments=tools.assessments();boolean filtered=false;String status="CHECKED";
            if(assessments.isEmpty()){
                status=failed?"TEMPORARILY_UNAVAILABLE":"NOT_CHECKED";
                answer=failed?"本轮服务暂不可用，尚未取得订单与政策检查结果。":"本轮没有实际工具检查结果，不能认定订单已获准退货。请明确订单和退货原因。";
            } else if(failed||AfterSaleExplanationGuard.shouldReplace(answer,assessments)){
                filtered=true;status=failed?"EXPLANATION_UNAVAILABLE":"CHECKED";
                answer="请以本轮结构化检查结果为准："+String.join("；",assessments.stream().map(Assessment::explanation).toList())+" 本次仅为只读预检查，没有提交申请或执行退款。";
            }
            // Memory Advisor 可能已经保存模型原文；重写为实际对用户返回的版本，防止被拦截承诺污染后续历史。
            save(key,history,message,answer);
            return new Result(requestId,status,answer,assessments,filtered,"LOCAL_FIXTURE");
        }
    }
    /** 会话归属必须在调用前验证，清除不能靠猜测外部 UUID。 */
    public void clear(String key){synchronized(KnowledgeConversationLocks.forKey(key)){memory.clear(key);}}
    private Set<String> orders(String value){var result=new LinkedHashSet<String>();var m=ORDER.matcher(value);while(m.find())result.add(m.group().toUpperCase(Locale.ROOT));return result;}
    private void save(String key,List<Message> history,String question,String answer){var messages=new ArrayList<>(history);messages.add(new UserMessage(question));messages.add(new AssistantMessage(answer));memory.clear(key);memory.add(key,messages);}
}
