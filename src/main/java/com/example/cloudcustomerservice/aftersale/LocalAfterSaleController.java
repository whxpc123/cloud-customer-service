package com.example.cloudcustomerservice.aftersale;

import jakarta.servlet.http.*;
import java.net.URI;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;

/**
 * 受控本地教学入口：只允许回环访问，固定演示账户由服务器创建，绝不读取演示身份头。
 * HttpSession 中的会话注册表是真正的所有权检查，但不是生产登录；不能接入真实订单或对外部署。
 */
@RestController
@RequestMapping("/internal/after-sale")
@Profile("local & knowledge")
public class LocalAfterSaleController {
    private final AfterSaleChatService chat;
    public LocalAfterSaleController(AfterSaleChatService chat){this.chat=chat;}
    /** 本地访问与 Origin/Host 约束防止把教学会话当跨站业务 API；修改请求另有会话 CSRF token。 */
    @ModelAttribute public void localOnly(HttpServletRequest request){
        if(!Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(request.getRemoteAddr())
                ||!Set.of("localhost","127.0.0.1","[::1]","::1").contains(request.getServerName()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅供本机教学使用");
        String origin=request.getHeader("Origin");
        if(origin!=null){try {URI u=URI.create(origin);int port=u.getPort()<0?("https".equals(u.getScheme())?443:80):u.getPort();
            if(!request.getScheme().equals(u.getScheme())||!request.getServerName().replace("[","").replace("]","").equals(u.getHost().replace("[","").replace("]",""))||port!=request.getServerPort())throw new IllegalArgumentException();}
            catch(RuntimeException ex){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"不允许跨站请求");}}
    }
    @GetMapping(produces="text/html;charset=UTF-8") public Resource page(){return new ClassPathResource("aftersale-lab/index.html");}
    /** 每个浏览器会话独立令牌；只绑定固定的本地样例 1001，不能通过请求指定用户或租户。 */
    @GetMapping("/session") public Map<String,Object> session(HttpServletRequest request){var s=state(request,true);return Map.of("csrfToken",s.csrf,"dataMode","LOCAL_FIXTURE","demoUser",1001,"message","本地演示账户，未连接真实登录或订单系统；订单日期为固定教学样例。");}
    @PostMapping("/conversations") public Map<String,String> create(HttpServletRequest request){var s=authorized(request);synchronized(s){
        if(s.conversations.size()>=10)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"每个浏览器会话最多十个会话");
        String id=UUID.randomUUID().toString();s.conversations.put(id,"after-sale/"+s.owner+"/"+id);return Map.of("conversationId",id);}}
    public record Question(String message){public Question{if(message==null||message.isBlank()||message.length()>4000)throw new IllegalArgumentException("问题须为 1～4000 字符");}}
    @PostMapping("/conversations/{id}/messages") public AfterSaleChatService.Result answer(HttpServletRequest request,@PathVariable String id,@RequestBody Question body){
        var s=authorized(request);String key=owned(s,id);return chat.answer(s.actor,key,body.message());}
    @DeleteMapping("/conversations/{id}/memory") @ResponseStatus(HttpStatus.NO_CONTENT) public void clear(HttpServletRequest request,@PathVariable String id){chat.clear(owned(authorized(request),id));}
    /** 未注册/其他 Session 的 UUID 都返回同一 404；先检查再读记忆、调用模型。 */
    private String owned(State s,String id){synchronized(s){String key=s.conversations.get(id);if(key==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"会话不存在或不可访问");return key;}}
    private State authorized(HttpServletRequest request){var s=state(request,false);if(!s.csrf.equals(request.getHeader("X-AfterSale-CSRF")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"会话校验失败，请刷新页面");return s;}
    private State state(HttpServletRequest request,boolean create){
        HttpSession session=request.getSession(create);if(session==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先建立本地会话");
        synchronized(session){Object value=session.getAttribute("afterSaleState");if(value instanceof State s)return s;
            if(!create)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先建立本地会话");
            var s=new State();session.setMaxInactiveInterval(1800);session.setAttribute("afterSaleState",s);return s;}
    }
    /** Session 过期时释放对应记忆；最多十个会话，身份/归属永远不从浏览器正文恢复。 */
    private final class State implements HttpSessionBindingListener {
        final Actor actor=new Actor("tenant-yunshan",1001);final String owner=UUID.randomUUID().toString(),csrf=UUID.randomUUID().toString();
        final Map<String,String> conversations=new HashMap<>();
        @Override public void valueUnbound(HttpSessionBindingEvent event){synchronized(this){conversations.values().forEach(chat::clear);conversations.clear();}}
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST) public Map<String,String> invalid(IllegalArgumentException ex){return Map.of("message",ex.getMessage());}
}
