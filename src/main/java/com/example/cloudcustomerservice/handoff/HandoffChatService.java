package com.example.cloudcustomerservice.handoff;

import com.example.cloudcustomerservice.routing.*;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import static com.example.cloudcustomerservice.handoff.HandoffModel.*;

/** 事务外调用模型，事务内接收用户消息和正式发布。转人工 API 不等待模型，也不依赖 AI 摘要。 */
@Service
@Profile("local & knowledge")
public class HandoffChatService {
    private final HumanHandoffService handoff;
    private final RoutedCustomerService robot;
    public HandoffChatService(HumanHandoffService handoff,RoutedCustomerService robot){this.handoff=handoff;this.robot=robot;}
    public Delivery answer(Actor actor,UUID id,UUID messageId,String text){
        Turn turn=handoff.begin(actor,id,messageId,text,CustomerRouter.isExplicitHumanCommand(text));
        if(turn.generationId()==null)return new Delivery(turn.receipt(),false,turn.replay()?"REPLAY":(CustomerRouter.isExplicitHumanCommand(text)?"HANDOFF_ACCEPTED":"USER_SAVED"),null);
        var context=new RoutingConversation(id.toString(),turn.history());
        var candidate=robot.answer(new com.example.cloudcustomerservice.aftersale.AfterSaleModel.Actor(actor.tenantId(),actor.accountId()),context,text);
        return handoff.finish(actor,id,turn,candidate);
    }
}
