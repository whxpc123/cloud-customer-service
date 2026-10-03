package com.example.cloudcustomerservice.routing;

import com.example.cloudcustomerservice.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import static org.assertj.core.api.Assertions.*;

/** 不依赖数据库验证缺配置时不创建默认账号，固定身份/角色不由登录表单指定。 */
class HandoffAccountTest {
    @Test void noPasswordMeansNoAccount(){
        var users=new HandoffSecurityConfiguration().handoffUsers(new MockEnvironment());
        assertThatThrownBy(()->users.loadUserByUsername("customer1001")).isInstanceOf(UsernameNotFoundException.class);
    }
    @Test void passwordIsEncodedAndRoleComesFromServer(){
        var env=new MockEnvironment().withProperty("handoff.accounts.support9001.password","only-test-long-password");
        var user=(HandoffPrincipal)new HandoffSecurityConfiguration().handoffUsers(env).loadUserByUsername("support9001");
        assertThat(user.actor().accountId()).isEqualTo(9001);assertThat(user.getPassword()).doesNotContain("only-test-long-password");
        assertThat(PasswordEncoderFactories.createDelegatingPasswordEncoder().matches("only-test-long-password",user.getPassword())).isTrue();
        assertThat(user.getAuthorities()).extracting(a->a.getAuthority()).containsExactly("support:serve");
    }
    @Test void weakConfiguredPasswordFailsStartup(){
        assertThatThrownBy(()->new HandoffSecurityConfiguration().handoffUsers(new MockEnvironment().withProperty("handoff.accounts.customer1001.password","short")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
