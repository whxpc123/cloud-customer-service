package com.example.cloudcustomerservice.aftersale;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import java.lang.reflect.Type;

/** 工具结果独立序列化：时间统一为带时区的 ISO 文本，避免供应商面对数字时间戳自行换算日期。 */
public final class AfterSaleToolResultConverter implements ToolCallResultConverter {
    private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    @Override public String convert(Object result,Type returnType){
        try{return json.writeValueAsString(result);}
        catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalStateException("Cannot serialize assessment");}
    }
}
