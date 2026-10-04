package com.example.aftersalereceiver.inbox;

import java.security.interfaces.RSAPublicKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

/**
 * 只在显式 local-jwt 配置下启用的本机教学签发器信任。
 * 接收进程只读取公钥；仍真实验证 RS256 签名、时间、issuer 和 audience。
 * 正式配置使用 Boot 的 issuer discovery，不将本机密钥带入生产环境。
 */
@Configuration(proxyBeanMethods = false)
@Profile("local-jwt")
public class LocalJwtConfiguration {
    @Bean
    JwtDecoder localDecoder(@Value("${app.local-jwt.public-key}") Resource key) throws Exception {
        RSAPublicKey publicKey;
        try (var input = key.getInputStream()) {
            publicKey = RsaKeyConverters.x509().convert(input);
        }
        var decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience() != null
                && jwt.getAudience().contains("after-sale-receiver")
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null));
        // 除框架默认的时效校验外，要求确实存在过期时间，避免无期限的本地凭证。
        OAuth2TokenValidator<Jwt> expiry = jwt -> jwt.getExpiresAt() != null
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Missing expiry", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer("urn:yunshan:local-service-issuer"), audience, expiry));
        return decoder;
    }
}
