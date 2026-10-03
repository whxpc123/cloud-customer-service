package com.example.cloudcustomerservice.routing;

import com.example.cloudcustomerservice.aftersale.AfterSaleModel.Actor;
import jakarta.servlet.http.*;
import java.net.URI;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 第十六章受控本地入口。固定演示身份不是生产登录；回环、同源、Session、CSRF 和会话注册表均实际校验。
 * 请求正文只能传 message，不能上传可信历史/租户/身份/接管状态；旧教学入口保持可用。
 */
@RestController
@RequestMapping("/internal/routing")
@Profile("local & knowledge")
public class LocalRoutingController {
    private final RoutedCustomerService service;
    private final RoutingStatistics statistics;
    public LocalRoutingController(RoutedCustomerService service, RoutingStatistics statistics) {
        this.service = service; this.statistics = statistics;
    }

    /** Profile 不是访问控制；只开放本地 Host，若有 Origin 必须与请求同源。 */
    @ModelAttribute public void localOnly(HttpServletRequest request) {
        if (!Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(request.getRemoteAddr())
                || !Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(request.getServerName()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅供本机教学使用");
        String origin = request.getHeader("Origin");
        if (origin != null) {
            try {
                URI uri = URI.create(origin);
                int port = uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
                if (!request.getScheme().equals(uri.getScheme()) || uri.getHost() == null
                        || !request.getServerName().replace("[", "").replace("]", "").equals(uri.getHost().replace("[", "").replace("]", ""))
                        || request.getServerPort() != port) throw new IllegalArgumentException();
            } catch (RuntimeException ex) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不允许跨站请求"); }
        }
    }
    @GetMapping(produces = "text/html;charset=UTF-8") public Resource page() { return new ClassPathResource("routing-lab/index.html"); }
    /** Cookie Session 提供归属边界；用户不能使用 X-Demo-User-Id 切换本入口的固定账户。 */
    @GetMapping("/session") public Map<String, Object> session(HttpServletRequest request) {
        State s = state(request, true);
        return Map.of("csrfToken", s.csrf, "demoUser", 1001, "dataMode", "LOCAL_FIXTURE", "humanConnected", false);
    }
    @PostMapping("/conversations") public Map<String, String> create(HttpServletRequest request) {
        State s = authorized(request);
        synchronized (s) {
            if (s.conversations.size() >= 10) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "每个浏览器会话最多十个会话，请清空已有会话继续");
            var conversation = new RoutingConversation(); s.conversations.put(conversation.id, conversation);
            return Map.of("conversationId", conversation.id);
        }
    }
    public record Question(String message) { public Question { new CustomerRouter.Input(message, ""); } }
    /** 先验证 Session、CSRF、会话所有权，之后服务才可读历史、判断接管状态和调用路由器。 */
    @PostMapping("/conversations/{id}/messages")
    public RoutedCustomerService.Response answer(HttpServletRequest request, @PathVariable String id, @RequestBody Question body) {
        State s = authorized(request);
        return service.answer(s.actor, owned(s, id), body.message());
    }
    @DeleteMapping("/conversations/{id}/memory") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(HttpServletRequest request, @PathVariable String id) { service.clear(owned(authorized(request), id)); }
    /** 诊断不使用已保存历史，不执行业务；仍要求本地 Session 和 CSRF，避免跨站消耗分类额度。 */
    @PostMapping("/decide") public RoutedCustomerService.Diagnostic decide(HttpServletRequest request, @RequestBody Question body) {
        authorized(request); return service.decide(body.message());
    }
    @GetMapping("/metrics") public Map<String, Object> metrics(HttpServletRequest request) { state(request, false); return statistics.snapshot(); }
    private RoutingConversation owned(State s, String id) {
        synchronized (s) {
            var conversation = s.conversations.get(id);
            if (conversation == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或不可访问");
            return conversation;
        }
    }
    private State authorized(HttpServletRequest request) {
        State s = state(request, false);
        if (!s.csrf.equals(request.getHeader("X-Routing-CSRF"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "会话校验失败，请刷新页面");
        return s;
    }
    private State state(HttpServletRequest request, boolean create) {
        HttpSession session = request.getSession(create);
        if (session == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先建立本地会话");
        synchronized (session) {
            Object value = session.getAttribute("routingState");
            if (value instanceof State s) return s;
            if (!create) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先建立本地会话");
            State s = new State(); session.setMaxInactiveInterval(1800); session.setAttribute("routingState", s); return s;
        }
    }
    /** Session 闲置过期时释放所有真实对话窗口；模型工作记忆已在每轮 finally 中清除。 */
    private final class State implements HttpSessionBindingListener {
        final Actor actor = new Actor("tenant-yunshan", 1001);
        final String csrf = UUID.randomUUID().toString();
        final Map<String, RoutingConversation> conversations = new HashMap<>();
        @Override public void valueUnbound(HttpSessionBindingEvent event) {
            synchronized (this) { conversations.values().forEach(service::clear); conversations.clear(); }
        }
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException ex) { return Map.of("message", ex.getMessage()); }
}
