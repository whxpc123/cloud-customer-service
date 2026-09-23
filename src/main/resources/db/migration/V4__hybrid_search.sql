-- 第十四章：所有现有/未来写入路径统一维护编码元数据，旧迁移保持不变。
CREATE FUNCTION ai.knowledge_business_codes() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE codes jsonb;
BEGIN
    SELECT coalesce(jsonb_agg(DISTINCT matches.parts[2]), '[]'::jsonb) INTO codes
    FROM regexp_matches(upper(coalesce(NEW.content,'') || ' ' || coalesce(NEW.metadata->>'couponCode','') || ' ' ||
        coalesce(NEW.metadata->>'productCode','') || ' ' || coalesce(NEW.metadata->>'policyCode','')),
        '(^|[^A-Z0-9_.-])((CPN|SKU)-[A-Z0-9]{4,16}|POLICY-[0-9]+(\.[0-9]+)*)(?=$|[^A-Z0-9_.-])', 'g') AS matches(parts);
    NEW.metadata := jsonb_set(NEW.metadata::jsonb, '{businessCodes}', codes)::json;
    RETURN NEW;
END $$;
CREATE TRIGGER knowledge_business_codes_before_write BEFORE INSERT OR UPDATE OF content,metadata
    ON ai.knowledge_vector_store FOR EACH ROW EXECUTE FUNCTION ai.knowledge_business_codes();
-- 触发一次回填，不改知识正文、向量、发布状态或版本。
UPDATE ai.knowledge_vector_store SET metadata=metadata;
ALTER TABLE ai.knowledge_vector_store ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
    to_tsvector('simple'::regconfig, coalesce(content,'') || ' ' || coalesce(metadata->>'sourceId','') || ' ' ||
      coalesce(metadata->>'category','') || ' ' || coalesce(metadata->>'businessCodes',''))
) STORED;
CREATE INDEX knowledge_search_vector_gin ON ai.knowledge_vector_store USING gin(search_vector);
CREATE INDEX knowledge_business_codes_gin ON ai.knowledge_vector_store USING gin((metadata::jsonb->'businessCodes'));
