package com.example.cloudcustomerservice.acceptance;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 验收专用数据库：独立容器、随机端口、无持久卷，销毁容器不会影响用户知识库。 */
@TestConfiguration(proxyBeanMethods=false)
public class AcceptanceDatabase {
    public static final String IMAGE="pgvector/pgvector:0.8.2-pg17@sha256:feb68f4f15446397d8cac7f4fe48fe4586de83160d1fc48b46283312d1a33966";
    /** ServiceConnection 优先于应用的本地数据源设置，口令由测试容器管理，不写验收报告。 */
    @Bean @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("stage1_acceptance");
    }
}
