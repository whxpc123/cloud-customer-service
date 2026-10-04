package com.example.aftersalereceiver.inbox;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** 接收端仅开放带 scope 的服务 POST；独立于客服网页的 Cookie / CSRF 配置。 */
@Configuration
public class ReceiverSecurityConfiguration {

    private static final String PATH =
            "/integration/after-sales/applications";

    @Bean
    public SecurityFilterChain receiverSecurity(
            HttpSecurity http) throws Exception {

        return http
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(
                                DispatcherType.ERROR
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                PATH
                        )
                        .hasAuthority(
                                "SCOPE_after-sale.ingest"
                        )
                        .requestMatchers(HttpMethod.POST, PATH + "/lookup")
                        .hasAuthority("SCOPE_after-sale.reconcile")
                        .anyRequest().denyAll()
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers(PATH, PATH + "/lookup")
                )
                .oauth2ResourceServer(resourceServer ->
                        resourceServer.jwt(
                                Customizer.withDefaults()
                        )
                )
                .build();
    }
}
