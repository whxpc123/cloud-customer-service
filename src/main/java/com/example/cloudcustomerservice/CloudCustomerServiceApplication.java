package com.example.cloudcustomerservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 整个学习项目的启动入口。Spring Boot 从本包向下扫描控制器、服务和配置类。
 * 在 IDEA 运行 main 方法；共享运行配置启用 local、knowledge，模型密钥由环境变量提供。
 */
@SpringBootApplication
public class CloudCustomerServiceApplication {

    /**
     * 启动 Spring 容器及内嵌 Web 服务；命令行参数可覆盖 application.yml。
     * @param args IDEA 或命令行传入的启动参数，如激活环境和服务端口
     */
    public static void main(String[] args) {
        SpringApplication.run(CloudCustomerServiceApplication.class, args);
    }
}
