package com.example.cloudcustomerservice.security;

import com.example.cloudcustomerservice.handoff.HandoffModel.Actor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 认证适配边界：不从请求正文、演示身份头或任意用户名数字解析租户/账户。 */
public final class HandoffIdentity {
    private HandoffIdentity() { }
    public static HandoffPrincipal principal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof HandoffPrincipal p))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先使用本地账户登录");
        return p;
    }
    public static Actor actor() { return principal().actor(); }
    /** 即便其他 Java 调用者误传 Actor，也不能借当前登录权限访问另一个主体的数据。 */
    public static void requireSame(Actor actor) {
        if (!actor().equals(actor)) throw new AccessDeniedException("身份与已认证账户不一致");
    }
}
