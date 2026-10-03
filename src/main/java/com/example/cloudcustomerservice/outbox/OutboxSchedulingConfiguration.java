package com.example.cloudcustomerservice.outbox;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 只有显式启用投递 Profile 才启动定时任务，普通运行只保存待发意图。 */
@Profile("local & knowledge & outbox-delivery")
@Configuration
@EnableScheduling
public class OutboxSchedulingConfiguration {
}
