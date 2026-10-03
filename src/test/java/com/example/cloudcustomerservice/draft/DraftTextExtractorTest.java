package com.example.cloudcustomerservice.draft;

import jakarta.validation.Validation;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实 ChatClient 转换器和 Bean Validation；不把 Mock ProposedText 当成结构化输出验证。 */
class DraftTextExtractorTest {
    final ChatModel model=mock(ChatModel.class);
    final jakarta.validation.ValidatorFactory validators=Validation.buildDefaultValidatorFactory();
    DraftTextExtractor extractor;
    @BeforeEach void setup(){when(model.getDefaultOptions()).thenReturn(ChatOptions.builder().build());extractor=new DraftTextExtractor(model,validators.getValidator(),false);}
    @AfterEach void close(){extractor.shutdown();validators.close();}
    static ChatResponse text(String value){return new ChatResponse(List.of(new Generation(new AssistantMessage(value))));}
    @Test void actualSchemaOnlyContainsUserStatementsAndNegationIsPreserved(){
        when(model.call(any(Prompt.class))).thenAnswer(i->{Prompt p=i.getArgument(0);String all=p.getInstructions().toString();
            assertThat(all).contains("userDescription","requestedHandling","软件尚未激活").doesNotContain("confirmDraft");
            assertThat(p.getOptions().getTemperature()).isZero();
            return text("{\"userDescription\":\"软件尚未激活\",\"requestedHandling\":\"待用户补充\"}");});
        var result=extractor.extract(UUID.randomUUID(),"用户称软件尚未激活。");assertThat(result.userDescription()).isEqualTo("软件尚未激活");verify(model,times(1)).call(any(Prompt.class));
    }
    @ParameterizedTest @ValueSource(strings={"null","{}","not json","{\"userDescription\":\" \",\"requestedHandling\":\"售后\"}","{\"userDescription\":\"故障\",\"requestedHandling\":\"\"}"})
    void malformedOrMissingTextFailsExplicitly(String output){when(model.call(any(Prompt.class))).thenReturn(text(output));assertThatThrownBy(()->extractor.extract(UUID.randomUUID(),"待整理"))
            .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(503));verify(model,times(1)).call(any(Prompt.class));}
    @Test void overlyLongFieldsFailValidation(){when(model.call(any(Prompt.class))).thenReturn(text("{\"userDescription\":\""+"字".repeat(1001)+"\",\"requestedHandling\":\"售后\"}"));
        assertThatThrownBy(()->extractor.extract(UUID.randomUUID(),"待整理")).isInstanceOf(ResponseStatusException.class);
        when(model.call(any(Prompt.class))).thenReturn(text("{\"userDescription\":\"故障\",\"requestedHandling\":\""+"字".repeat(301)+"\"}"));assertThatThrownBy(()->extractor.extract(UUID.randomUUID(),"待整理")).isInstanceOf(ResponseStatusException.class);}
    @Test void invalidInputAndGlobalToolsStopBeforeModel(){for(String input:new String[]{null," ","字".repeat(16001)})assertThatThrownBy(()->extractor.extract(UUID.randomUUID(),input)).isInstanceOf(IllegalArgumentException.class);
        when(model.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().toolNames("unsafe").build());assertThatThrownBy(()->extractor.extract(UUID.randomUUID(),"候选")).isInstanceOf(ResponseStatusException.class);verify(model,never()).call(any(Prompt.class));}
    @Test void timeoutDoesNotRetryOrPermitUnboundedNewWorkers()throws Exception{
        extractor.shutdown();extractor=new DraftTextExtractor(model,validators.getValidator(),false,Duration.ofMillis(80));
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        when(model.call(any(Prompt.class))).thenAnswer(i->{entered.countDown();release.await(3,TimeUnit.SECONDS);return text("{}");});
        try{for(int i=0;i<2;i++)assertThatThrownBy(()->extractor.extract(UUID.randomUUID(),"候选")).isInstanceOf(ResponseStatusException.class);
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->extractor.extract(UUID.randomUUID(),"候选")).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(429));verify(model,times(2)).call(any(Prompt.class));
        }finally{release.countDown();}
    }
}
