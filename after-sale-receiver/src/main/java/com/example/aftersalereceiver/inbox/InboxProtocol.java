package com.example.aftersalereceiver.inbox;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import jakarta.validation.Validator;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import static com.example.aftersalereceiver.inbox.InboxModels.*;

/** 严格版本协议：重复键、尾随对象、未知字段及数字截断一律拒绝。 */
@Component
public class InboxProtocol {

    public static final int MAX_BYTES = 65_536;

    private final ObjectReader reader;
    private final Validator validator;

    public InboxProtocol(
            ObjectMapper mapper,
            Validator validator) {

        ObjectMapper strict = mapper.copy()
                .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(
                        JsonParser.Feature
                                .STRICT_DUPLICATE_DETECTION
                )
                .enable(
                        DeserializationFeature
                                .FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature
                                .FAIL_ON_TRAILING_TOKENS
                )
                .disable(
                        DeserializationFeature
                                .ACCEPT_FLOAT_AS_INT
                );

        this.reader = strict.readerFor(
                CreateApplicationEvent.class
        );

        this.validator = validator;
    }

    public CreateApplicationEvent parse(String json) {

        if (json == null
                || json.length() > MAX_BYTES
                || json.getBytes(StandardCharsets.UTF_8)
                        .length > MAX_BYTES) {

            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "EVENT_TOO_LARGE"
            );
        }

        CreateApplicationEvent event;

        try {
            event = reader.readValue(json);
        }
        catch (JsonProcessingException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_EVENT_JSON"
            );
        }

        if (event == null
                || !validator.validate(event).isEmpty()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_EVENT_FIELDS"
            );
        }

        return event;
    }
}
