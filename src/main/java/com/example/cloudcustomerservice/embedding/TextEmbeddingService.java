package com.example.cloudcustomerservice.embedding;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

@Service
public class TextEmbeddingService {
    public static final int MAX_TEXT_LENGTH = 2000;
    // text-embedding-v4 的单批限制为 10；排序最多 21 条输入，分批后保持顺序。
    private static final int BATCH_SIZE = 10;
    private static final Logger log = LoggerFactory.getLogger(TextEmbeddingService.class);
    private final EmbeddingModel embeddingModel;

    public TextEmbeddingService(EmbeddingModel embeddingModel) { this.embeddingModel = embeddingModel; }

    public float[] embed(String text) { return embedAll(List.of(validated(text, "text"))).get(0); }

    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty() || texts.size() > 21) {
            throw new IllegalArgumentException("Expected 1 to 21 texts");
        }
        texts.forEach(text -> validated(text, "text"));
        var inputs = List.copyOf(texts);
        long started = System.nanoTime();
        try {
            List<float[]> vectors = new ArrayList<>();
            int dimensions = -1;
            for (int offset = 0; offset < inputs.size(); offset += BATCH_SIZE) {
                var batch = inputs.subList(offset, Math.min(offset + BATCH_SIZE, inputs.size()));
                var result = embeddingModel.embed(batch);
                if (result == null || result.size() != batch.size()) throw new IllegalStateException("Invalid vector count");
                for (float[] vector : result) {
                    if (vector == null || vector.length == 0 || (dimensions != -1 && vector.length != dimensions)) {
                        throw new IllegalStateException("Invalid vector dimensions");
                    }
                    boolean nonzero = false;
                    for (float value : vector) {
                        if (!Float.isFinite(value)) throw new IllegalStateException("Invalid vector value");
                        nonzero |= value != 0;
                    }
                    if (!nonzero) throw new IllegalStateException("Zero model vector");
                    dimensions = vector.length;
                    vectors.add(vector);
                }
            }
            log.info("[EMBEDDING RESULT] texts={} batches={} dimensions={} elapsedMs={}",
                    inputs.size(), (inputs.size() + BATCH_SIZE - 1) / BATCH_SIZE, dimensions,
                    (System.nanoTime() - started) / 1_000_000);
            return List.copyOf(vectors);
        } catch (RuntimeException ex) {
            log.warn("[EMBEDDING ERROR] texts={} errorType={}", inputs.size(), ex.getClass().getSimpleName());
            throw new EmbeddingUnavailableException();
        }
    }

    public static String validated(String text, String field) {
        if (text == null || text.isBlank() || text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(field + " must contain 1 to 2000 characters");
        }
        return text;
    }
}
