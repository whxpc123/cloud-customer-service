package com.example.cloudcustomerservice.security;

import com.example.cloudcustomerservice.handoff.HandoffModel.Actor;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/** 只把新统一入口和人工服务纳入登录；旧章节保留原教学契约，不能据此对外部署整个应用。 */
@Configuration
@EnableMethodSecurity
public class HandoffSecurityConfiguration {
    /** 缺密码的账户不注册，绝不使用默认密码或仅凭 accountId 自动登录。 */
    @Bean public UserDetailsService handoffUsers(Environment env) {
        var users = new HashMap<String, HandoffPrincipal>();
        var encoder = new BCryptPasswordEncoder();
        for (String name : List.of("customer1001", "customer2002", "support9001", "support9002")) {
            String password = env.getProperty("handoff.accounts." + name + ".password");
            if (password == null || password.isBlank()) continue;
            if (password.length() < 16) throw new IllegalArgumentException("本地接待账户密码至少 16 个字符，请重新配置账户文件");
            boolean support = name.startsWith("support");
            long id = switch (name) { case "customer1001" -> 1001; case "customer2002" -> 2002; case "support9001" -> 9001; default -> 9002; };
            users.put(name, new HandoffPrincipal(name, "{bcrypt}" + encoder.encode(password), new Actor("tenant-yunshan", id),
                    List.of(new SimpleGrantedAuthority(support ? "support:serve" : "customer:chat"))));
        }
        return username -> { var user = users.get(username); if (user == null) throw new UsernameNotFoundException("账户不可用"); return user; };
    }

    /** Session 登录含 CSRF 和会话固定攻击防护；登录前后的 token 需重新读取。 */
    @Bean @Order(1) @Profile("local & knowledge")
    public SecurityFilterChain handoffSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/internal/routing/**", "/internal/handoff/**", "/api/handoff/**", "/api/support/**", "/internal/stream-lab/**", "/internal/draft-agent/**", "/internal/draft-tasks/**", "/internal/local-draft-tasks/**")
                .addFilterBefore(new LocalAccessFilter(), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                    // 异步完成分派不再次执行控制器；原始请求已校验，状态轮询仍逐次检查 Session。
                    .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                    .requestMatchers(HttpMethod.GET, "/internal/routing", "/internal/handoff/session", "/internal/stream-lab", "/internal/draft-agent", "/internal/draft-tasks", "/internal/draft-tasks/hitl", "/internal/draft-tasks/flow", "/internal/draft-tasks/submission", "/internal/local-draft-tasks").permitAll()
                    .requestMatchers("/internal/handoff/login").permitAll()
                    .requestMatchers("/api/support/**").hasAuthority("support:serve")
                    .requestMatchers("/internal/stream-lab/**").authenticated()
                    .requestMatchers("/internal/handoff/logout").authenticated()
                    .anyRequest().hasAuthority("customer:chat"))
                .formLogin(f -> f.loginProcessingUrl("/internal/handoff/login")
                    .successHandler((q,r,a)->r.setStatus(204))
                    .failureHandler((q,r,e)->error(r,401,"账户或密码不正确")))
                .logout(l -> l.logoutUrl("/internal/handoff/logout").logoutSuccessHandler((q,r,a)->r.setStatus(204)))
                .exceptionHandling(e -> e.authenticationEntryPoint((q,r,x)->error(r,401,"请先登录"))
                    .accessDeniedHandler((q,r,x)->error(r,403,"权限或 CSRF 校验失败，请重新登录或刷新页面")));
        return http.build();
    }
    /** 保持第一至十五章实验 API 的历史行为；新增人工 API 不匹配此分支。 */
    @Bean @Order(2) public SecurityFilterChain chapterCompatibility(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).csrf(c -> c.disable()).build();
    }
    private static void error(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code); response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
    /** 只用于新入口。拒绝非回环与跨站访问，不信任 Forwarded / 演示身份头。 */
    private static final class LocalAccessFilter extends OncePerRequestFilter {
        @Override protected void doFilterInternal(HttpServletRequest q,HttpServletResponse r,FilterChain chain)throws ServletException,IOException {
            if (!Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(q.getRemoteAddr())
                    || !Set.of("localhost","127.0.0.1","[::1]","::1").contains(q.getServerName())) { error(r,403,"仅供本机教学使用"); return; }
            String origin=q.getHeader("Origin");
            if(origin!=null)try {
                URI u=URI.create(origin);int port=u.getPort()<0?("https".equals(u.getScheme())?443:80):u.getPort();
                if(!q.getScheme().equals(u.getScheme())||u.getHost()==null||!q.getServerName().replace("[","").replace("]","").equals(u.getHost().replace("[","").replace("]",""))||port!=q.getServerPort())throw new IllegalArgumentException();
            } catch(RuntimeException ex){error(r,403,"不允许跨站请求");return;}
            chain.doFilter(q,r);
        }
    }
}
