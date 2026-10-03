package com.example.cloudcustomerservice.stream;

import com.example.cloudcustomerservice.handoff.HandoffModel.Actor;
import com.example.cloudcustomerservice.security.*;
import jakarta.servlet.http.HttpSession;
import java.util.function.Supplier;
import org.springframework.security.core.context.*;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/** 线程切换时显式传递已验证身份；每次状态查询重查 Session，绝不关闭方法权限或全局共享 SecurityContext。 */
public record StreamIdentity(Actor actor, HttpSession session) {
    public static StreamIdentity capture(HttpSession session) { return new StreamIdentity(HandoffIdentity.actor(),session); }
    public <T> T read(Supplier<T> work) {
        var saved = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (!(saved instanceof SecurityContext context) || context.getAuthentication() == null
                || !context.getAuthentication().isAuthenticated()
                || !(context.getAuthentication().getPrincipal() instanceof HandoffPrincipal principal)
                || !actor.equals(principal.actor())
                || context.getAuthentication().getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("customer:chat"))) throw new IllegalStateException("stream authentication expired");
        var previous = SecurityContextHolder.getContext();
        var isolated = SecurityContextHolder.createEmptyContext(); isolated.setAuthentication(context.getAuthentication());
        SecurityContextHolder.setContext(isolated);
        try { return work.get(); } finally { SecurityContextHolder.setContext(previous); }
    }
}
