package com.example.cloudcustomerservice.security;

import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/** 登录状态与 CSRF 引导，不返回密码；账户身份仅在 Spring Security 成功验证后可见。 */
@RestController
@Profile("local & knowledge")
public class HandoffLoginController {
    @GetMapping("/internal/handoff/session")
    public Map<String,Object> session(Authentication authentication, CsrfToken csrf) {
        var result=new LinkedHashMap<String,Object>();
        result.put("csrfToken",csrf.getToken());result.put("csrfHeader",csrf.getHeaderName());
        boolean logged=authentication!=null&&authentication.getPrincipal() instanceof HandoffPrincipal;
        result.put("authenticated",logged);result.put("dataMode","LOCAL_ACCOUNTS_AND_ORDER_FIXTURES");
        if(logged){var p=(HandoffPrincipal)authentication.getPrincipal();result.put("username",p.username());result.put("accountId",p.actor().accountId());result.put("authorities",p.authorities().stream().map(a->a.getAuthority()).toList());}
        return result;
    }
}
