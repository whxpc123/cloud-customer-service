package com.example.cloudcustomerservice.handoff;

import com.example.cloudcustomerservice.security.HandoffIdentity;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static com.example.cloudcustomerservice.handoff.HandoffModel.*;

/** 使用独立命名空间避免与第四章演示接口重名；所有 Actor 都从已验证的 SecurityContext 取得。 */
@RestController
@Profile("local & knowledge")
public class HumanHandoffController {
    private final HumanHandoffService service;
    public HumanHandoffController(HumanHandoffService service){this.service=service;}
    @PostMapping("/api/handoff/conversations") @ResponseStatus(HttpStatus.CREATED)
    public Receipt create(){return service.createConversation(HandoffIdentity.actor());}
    @GetMapping("/api/handoff/conversations") public List<Receipt> list(){return service.conversations(HandoffIdentity.actor());}
    @PostMapping("/api/handoff/conversations/{id}/handoff") public Receipt request(@PathVariable UUID id){return service.request(HandoffIdentity.actor(),id);}
    @GetMapping("/api/handoff/conversations/{id}/handoff") public Receipt state(@PathVariable UUID id){return service.get(HandoffIdentity.actor(),id);}
    @GetMapping("/api/handoff/conversations/{id}/messages") public History history(@PathVariable UUID id,@RequestParam(defaultValue="0") long after){return service.history(HandoffIdentity.actor(),id,after);}
    @GetMapping("/api/support/queue") public List<Receipt> queue(){return service.waiting(HandoffIdentity.actor());}
    @PostMapping("/api/support/conversations/{id}/accept") public Receipt accept(@PathVariable UUID id){return service.accept(HandoffIdentity.actor(),id);}
    @PostMapping("/api/support/conversations/{id}/close") public Receipt close(@PathVariable UUID id){return service.close(HandoffIdentity.actor(),id);}
    @GetMapping("/api/support/conversations/{id}/messages") public History supportHistory(@PathVariable UUID id,@RequestParam(defaultValue="0") long after){return service.supportHistory(HandoffIdentity.actor(),id,after);}
}
