package com.example.cloudcustomerservice.routing;

import com.example.cloudcustomerservice.handoff.*;
import com.example.cloudcustomerservice.security.HandoffIdentity;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.*;
import org.springframework.web.bind.annotation.*;
import static com.example.cloudcustomerservice.handoff.HandoffModel.*;

/** 第十七章升级原统一入口：身份由 Spring Security 验证，归属、模式和历史均由数据库服务校验。 */
@RestController
@RequestMapping("/internal/routing")
@Profile("local & knowledge")
public class LocalRoutingController {
    private final HandoffChatService chat;
    private final HumanHandoffService handoff;
    private final RoutedCustomerService robot;
    private final RoutingStatistics statistics;
    public LocalRoutingController(HandoffChatService chat,HumanHandoffService handoff,RoutedCustomerService robot,RoutingStatistics statistics){this.chat=chat;this.handoff=handoff;this.robot=robot;this.statistics=statistics;}
    @GetMapping(produces="text/html;charset=UTF-8") public Resource page(){return new ClassPathResource("routing-lab/index.html");}
    /** 兼容上一章创建入口，返回已落库的真实接待回执。 */
    @PostMapping("/conversations") public Receipt create(){return handoff.createConversation(HandoffIdentity.actor());}
    public record Question(String message,UUID clientMessageId){public Question{new CustomerRouter.Input(message,"");if(clientMessageId==null)clientMessageId=UUID.randomUUID();}}
    @PostMapping("/conversations/{id}/messages") public Delivery answer(@PathVariable UUID id,@RequestBody Question question){return chat.answer(HandoffIdentity.actor(),id,question.clientMessageId(),question.message());}
    /** 此操作只重置机器人上下文，不删除正式记录，不改变接待模式。 */
    @DeleteMapping("/conversations/{id}/memory") public Receipt reset(@PathVariable UUID id){return handoff.resetMemory(HandoffIdentity.actor(),id);}
    @PostMapping("/decide") public RoutedCustomerService.Diagnostic decide(@RequestBody Question question){HandoffIdentity.actor();return robot.decide(question.message());}
    @GetMapping("/metrics") public Map<String,Object> metrics(){HandoffIdentity.actor();return statistics.snapshot();}
}
