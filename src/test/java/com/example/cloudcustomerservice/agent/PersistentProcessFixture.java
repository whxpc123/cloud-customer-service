package com.example.cloudcustomerservice.agent;

import com.example.cloudcustomerservice.CloudCustomerServiceApplication;
import com.example.cloudcustomerservice.aftersale.ReturnAssessmentService;
import java.nio.file.*;
import java.util.concurrent.CountDownLatch;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import static com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;
import static org.mockito.Mockito.*;

/** 仅在测试 classpath 中的子进程入口，不打入应用 JAR；以确定性闩锁制造执行中断，不访问真实模型。 */
public class PersistentProcessFixture {
    public static void main(String[] args)throws Exception {
        var context=new SpringApplicationBuilder(CloudCustomerServiceApplication.class,Fixtures.class).run(args);
        int port=((ServletWebServerApplicationContext)context).getWebServer().getPort();
        Files.writeString(Path.of(System.getenv("CH22_READY_FILE")),String.valueOf(port));
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class Fixtures {
        @Bean @Primary ChatModel processModel(){return new ChatModel(){
            @Override public ChatOptions getDefaultOptions(){return com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions.builder().model("process-controlled").build();}
            @Override public ChatResponse call(Prompt prompt){
                try{
                    Files.writeString(Path.of(System.getenv("CH22_CALLS_FILE")),"call\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                    String all=prompt.getInstructions().toString();
                    if(all.contains("BLOCK_FOR_CRASH")){
                        Files.writeString(Path.of(System.getenv("CH22_BLOCK_FILE")),"started");new CountDownLatch(1).await();
                    }
                    if(all.contains("second-button")&&!all.contains("first-fracture"))throw new IllegalStateException("历史消息未恢复");
                    return DraftTaskTest.next(prompt);
                }catch(Exception ex){Thread.currentThread().interrupt();throw new IllegalStateException("测试进程被中断",ex);}
            }
        };}
        @Bean @Primary ReturnAssessmentService processAssessment(){
            var service=mock(ReturnAssessmentService.class);
            when(service.assess(any(),anyString(),any())).thenReturn(DraftAgentTest.assessment("A10001",AssessmentStatus.NEED_QUALITY_VERIFICATION,true));return service;
        }
    }
}
