package com.example.cloudcustomerservice.knowledge.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.Pattern;
import com.example.cloudcustomerservice.knowledge.KnowledgeDocumentReader;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@Profile("local & knowledge")
public class KnowledgePreparationService {
    public static final int MAX_CHUNKS = 2000;
    private final KnowledgeDocumentReaderFactory readers;
    public KnowledgePreparationService(KnowledgeDocumentReaderFactory readers) { this.readers = readers; }

    public PreparedKnowledge text(String name, String version, String text, ChunkingOptions options) {
        var source = new KnowledgeSource(name, version, "pasted-text.txt", "TEXT");
        String checked = KnowledgeDocumentReader.normalize(text);
        return prepare(source, List.of(new Document(checked)), options);
    }
    public PreparedKnowledge file(String name, String version, MultipartFile file, ChunkingOptions options) {
        if (file == null || file.isEmpty() || file.getSize() > KnowledgeDocumentReader.MAX_FILE_BYTES)
            throw new IllegalArgumentException("文件须非空且不能超过 5 MB");
        String fileName = KnowledgeDocumentReaderFactory.fileName(file.getOriginalFilename());
        try { return bytes(name == null || name.isBlank() ? fileName : name, version, fileName, file.getBytes(), options); }
        catch (java.io.IOException ex) { throw new IllegalArgumentException("无法读取上传文件"); }
    }
    public PreparedKnowledge bytes(String name, String version, String fileName, byte[] bytes, ChunkingOptions options) {
        var source = new KnowledgeSource(name, version, fileName, KnowledgeDocumentReaderFactory.fileType(fileName));
        return prepare(source, readers.read(source, bytes, options), options);
    }
    public PreparedKnowledge prepare(KnowledgeSource source, List<Document> extracted, ChunkingOptions options) {
        var warnings = new LinkedHashSet<String>();
        if (source.fileType().equals("PDF")) warnings.add("PDF 表格行列关系和跨页条件可能不完整，请逐块核对；扫描页需要 OCR。");
        if (options.pdfTopLines() + options.pdfBottomLines() > 0) warnings.add("已按配置删除每页页首/页尾行，请确认没有误删正文。");
        var normalized = new ArrayList<Document>();
        int rawCharacters = 0;
        long replacementCharacters = 0;
        for (int index = 0; index < extracted.size(); index++) {
            Document original = extracted.get(index);
            String raw = original.getText() == null ? "" : original.getText(); rawCharacters += raw.length();
            if (rawCharacters > 50_000) throw new IllegalArgumentException("提取正文超过 50000 字符");
            if (raw.indexOf(0) >= 0) throw new IllegalArgumentException("正文包含无效的空字符，请检查文件编码");
            replacementCharacters += raw.chars().filter(c -> c == '\uFFFD').count();
            if (replacementCharacters > 20) throw new IllegalArgumentException("正文可能包含编码损坏，请检查乱码");
            String cleaned = clean(raw);
            if (cleaned.isBlank()) continue;
            var metadata = new HashMap<String,Object>();
            for (String key : List.of("title", "page_number", "end_page_number", "category")) {
                Object value = original.getMetadata().get(key);
                if (value instanceof String || value instanceof Number) metadata.put(key, value);
            }
            metadata.put("tenantId", source.tenantId());metadata.put("knowledgeBase", "after-sales");
            metadata.put("sourceId", source.sourceId());metadata.put("sourceName", source.sourceName());
            metadata.put("sourceTitle", source.sourceName());metadata.put("sourceVersion", source.sourceVersion());
            metadata.put("sourceFileName", source.fileName());metadata.put("language", "zh-CN");
            metadata.put("rawDocumentIndex", index);metadata.put("fileType", source.fileType());
            normalized.add(new Document(cleaned, metadata));
        }
        if (normalized.isEmpty()) throw new IllegalArgumentException("未提取到可用正文；扫描 PDF 请先做 OCR，空文件或仅代码块不能导入");
        var splitter = new CheckedTokenSplitter(options.chunkSize(), warnings);
        var chunks = new ArrayList<Document>();
        int total = 0;
        for (Document parent : normalized) {
            for (Document piece : splitter.apply(List.of(parent))) {
                String body = piece.getText().strip();
                if (body.isBlank()) continue;
                // 只继承已清理的来源 Metadata，避免 Splitter 注入随机 parent_document_id。
                var metadata = new HashMap<>(parent.getMetadata());
                String title = String.valueOf(metadata.getOrDefault("title", ""));
                String content = title.isBlank() ? body : title + "\n" + body;
                if (content.length() < 40) warnings.add("包含少于 40 字符的片段：为保留简短规则未自动丢弃，请检查是否只是目录或页码。");
                String hash = sha256(content);
                int index = chunks.size() + 1;
                if (index > MAX_CHUNKS) throw new IllegalArgumentException("切分结果超过 2000 块，请拆分文件");
                metadata.put("chunkIndex", index);metadata.put("chunkHash", hash);
                metadata.put("chunkingVersion", options.version());metadata.put("embeddingProfile", "dashscope-text-embedding-v4-1024");
                metadata.put("embeddingModel", "text-embedding-v4");metadata.put("embeddingDimensions", 1024);
                metadata.put("category", "UPLOADED_DOCUMENT");metadata.put("status", "PUBLISHED");
                String id = UUID.nameUUIDFromBytes((source.tenantId()+"|"+source.sourceId()+"|"+source.sourceVersion()+"|"+
                        options.version()+"|"+index+"|"+hash).getBytes(StandardCharsets.UTF_8)).toString();
                chunks.add(new Document(id, content, metadata));total += content.length();
            }
        }
        if (chunks.isEmpty()) throw new IllegalArgumentException("没有生成可用知识块");
        if (total < 100) warnings.add("总正文少于 100 字符，请确认是完整的简短规则，而不是提取失败。");
        return new PreparedKnowledge(source, options, extracted.size(), normalized.size(), rawCharacters, total, chunks, List.copyOf(warnings));
    }
    static String clean(String input) {
        return input.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r','\n').replace('\u00A0',' ')
                .replaceAll("[\\t\\x0B\\f ]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}","\n\n").strip();
    }
    public static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    /** 核对非空白正文不丢失；修复 Token 边界在中文/emoji 中间截断时的替换符问题。 */
    static final class CheckedTokenSplitter extends TokenTextSplitter {
        private final Set<String> warnings;
        private final com.knuddels.jtokkit.api.Encoding encoding = com.knuddels.jtokkit.Encodings.newLazyEncodingRegistry()
                .getEncoding(com.knuddels.jtokkit.api.EncodingType.CL100K_BASE);
        CheckedTokenSplitter(int size, Set<String> warnings) { super(size,180,0,MAX_CHUNKS,true); this.warnings=warnings; }
        @Override protected List<String> doSplit(String text, int size) {
            List<String> standard = super.doSplit(text, size);
            if (compact(text).equals(compact(String.join("", standard))) && standard.stream().allMatch(s->encoding.countTokens(s)<=size+8)) return standard;
            warnings.add("部分 Token 边界需要按完整 Unicode 字符修正，已保留原正文。");
            var result = new ArrayList<String>();
            int start=0;
            while (start<text.length()) {
                int low=1, high=Math.min(size*4,text.codePointCount(start,text.length())), best=1;
                while (low<=high) {
                    int mid=(low+high)/2;int end=text.offsetByCodePoints(start,mid);
                    if (encoding.countTokens(text.substring(start,end))<=size) {best=mid;low=mid+1;} else high=mid-1;
                }
                int end=text.offsetByCodePoints(start,best);
                String part=text.substring(start,end);
                if(end<text.length()) {
                    int boundary=-1;
                    for (char c:new char[]{'。','！','？','\n','.','!','?'}) boundary=Math.max(boundary,part.lastIndexOf(c));
                    if (boundary>Math.min(180,part.length()/2)) end=start+boundary+1;
                }
                String chunk=text.substring(start,end).strip();if(!chunk.isEmpty())result.add(chunk);start=end;
                if(result.size()>MAX_CHUNKS)throw new IllegalArgumentException("切分结果超过 2000 块");
            }
            return result;
        }
        private static String compact(String text) { return Pattern.compile("\\s+").matcher(text).replaceAll(""); }
    }
}
