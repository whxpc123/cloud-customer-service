package com.example.cloudcustomerservice.security;

import com.example.cloudcustomerservice.handoff.HandoffModel.Actor;
import java.util.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** 登录成功后的可信身份。只在服务器使用，密码摘要和整个 principal 都不能序列化给页面。 */
public record HandoffPrincipal(String username, String password, Actor actor,
        Collection<? extends GrantedAuthority> authorities) implements UserDetails {
    @Override public String getUsername() { return username; }
    @Override public String getPassword() { return password; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
}
