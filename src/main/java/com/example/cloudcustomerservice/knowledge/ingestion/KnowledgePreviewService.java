package com.example.cloudcustomerservice.knowledge.ingestion;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.example.cloudcustomerservice.knowledge.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 本地有界预览和后台任务；不保存原文件，重启后预览/任务状态失效。 */
@Service
@Profile("local & knowledge")
public class KnowledgePreviewService {
    public record ChunkPreview(int chunkIndex,int characters,String text,String documentId,Map<String,Object> metadata) { }
    public record Preview(String previewId,String expiresAt,String sourceId,String sourceName,String sourceVersion,
            String fileName,String chunkingVersion,int extractedDocuments,int normalizedDocuments,int rawCharacters,
            int chunks,int totalCharacters,int shortestChunk,int longestChunk,List<String> warnings,List<ChunkPreview> previews) { }
    public record Job(String jobId,String status,String message,CustomKnowledgeImportResult result) { }
    private static final class Entry {
        final PreparedKnowledge prepared; volatile Instant expiresAt; volatile Job job;
        Entry(PreparedKnowledge prepared,Instant expiresAt) {this.prepared=prepared;this.expiresAt=expiresAt;}
    }
    private final Map<String,Entry> previews=new LinkedHashMap<>();
    private final CustomKnowledgeImportService importer;
    private final Clock clock;
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{
        Thread t=new Thread(r,"knowledge-import");t.setDaemon(true);return t;
    },new ThreadPoolExecutor.AbortPolicy());
    @Autowired public KnowledgePreviewService(CustomKnowledgeImportService importer) {this(importer,Clock.systemUTC());}
    public KnowledgePreviewService(CustomKnowledgeImportService importer,Clock clock) {this.importer=importer;this.clock=clock;}

    public synchronized Preview save(PreparedKnowledge prepared) {
        prune();
        if(previews.size()>=32)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"预览已满，请稍后重试；预览保留 15 分钟");
        String id=UUID.randomUUID().toString();Entry entry=new Entry(prepared,clock.instant().plusSeconds(900));previews.put(id,entry);
        return response(id,entry);
    }
    public synchronized Job submit(String id) {
        prune();Entry entry=previews.get(id);
        if(entry==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"预览已过期，请重新预览");
        if(entry.job!=null&&!entry.job.status().equals("FAILED"))return entry.job;
        Job queued=new Job(UUID.randomUUID().toString(),"QUEUED","等待后台生成向量",null);entry.job=queued;
        try {
            executor.execute(()->{
                entry.job=new Job(queued.jobId(),"EMBEDDING","正在生成向量并保存知识",null);
                try {
                    var result=importer.importPrepared(entry.prepared);
                    entry.expiresAt=clock.instant().plusSeconds(900);
                    entry.job=new Job(queued.jobId(),"PUBLISHED","已导入；同名旧资料已整体替换",result);
                } catch(RuntimeException ex) {
                    entry.expiresAt=clock.instant().plusSeconds(900);
                    entry.job=new Job(queued.jobId(),"FAILED","导入失败，旧知识保留。请检查数据库与百炼服务，可重试当前预览。",null);
                }
            });
        } catch(RejectedExecutionException ex) {
            entry.job=null;throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"导入队列已满，请稍后重试");
        }
        return queued;
    }
    public synchronized Job job(String jobId) {
        prune();return previews.values().stream().map(e->e.job).filter(Objects::nonNull).filter(j->j.jobId().equals(jobId))
                .findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"任务已过期或应用已重启，请重新预览"));
    }
    private void prune() {
        previews.values().removeIf(e->clock.instant().isAfter(e.expiresAt)&&(e.job==null||Set.of("PUBLISHED","FAILED").contains(e.job.status())));
    }
    private Preview response(String id,Entry entry) {
        var p=entry.prepared;
        var chunks=p.chunks().stream().map(d->new ChunkPreview(((Number)d.getMetadata().get("chunkIndex")).intValue(),d.getText().length(),
                d.getText(),d.getId(),Map.copyOf(d.getMetadata()))).toList();
        var stats=chunks.stream().mapToInt(ChunkPreview::characters).summaryStatistics();
        return new Preview(id,entry.expiresAt.toString(),p.source().sourceId(),p.source().sourceName(),p.source().sourceVersion(),p.source().fileName(),
                p.options().version(),p.extractedDocuments(),p.normalizedDocuments(),p.rawCharacters(),chunks.size(),p.totalCharacters(),
                stats.getMin(),stats.getMax(),p.warnings(),chunks);
    }
    @PreDestroy public void close() {executor.shutdown();}
}
