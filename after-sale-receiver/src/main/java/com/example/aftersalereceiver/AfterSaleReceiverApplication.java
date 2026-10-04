package com.example.aftersalereceiver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 独立进程入口；仅连接接收方数据库，不扫描客服项目，也不调用大模型。 */
@SpringBootApplication
public class AfterSaleReceiverApplication {
    public static void main(String[] args) {
        SpringApplication.run(AfterSaleReceiverApplication.class, args);
    }
}
