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

/**
 * 第八章预览缓存和有界后台导入任务，仅用于本地单实例演示。
 * 最多保存 32 份预览；一个工作线程、四个排队位置限制远程向量请求压力。
 * 缓存映射用 synchronized 保护，工作线程通过 volatile 字段发布任务状态；不持久化任务。
 */
@Service
@Profile("local & knowledge")
public class KnowledgePreviewService {
    /**
     * 单块预览：序号从 1 开始，characters 为 UTF-16 长度，text 是完整正文。
     * documentId 和 metadata 用来核对实际即将落库的来源、页号、哈希与处理版本。
     */
    public record ChunkPreview(int chunkIndex,int characters,String text,String documentId,Map<String,Object> metadata) { }
    /**
     * 预览响应含令牌、ISO 到期时间、来源、处理统计、警告及全部块正文。
     * extractedDocuments/normalizedDocuments 是切分前段数，chunks 是切分后块数；不是文件数量。
     * 前端分批显示 previews，但服务端响应已包含全部预览块，尚未写入数据库。
     */
    public record Preview(String previewId,String expiresAt,String sourceId,String sourceName,String sourceVersion,
            String fileName,String chunkingVersion,int extractedDocuments,int normalizedDocuments,int rawCharacters,
            int chunks,int totalCharacters,int shortestChunk,int longestChunk,List<String> warnings,List<ChunkPreview> previews) { }
    /**
     * 后台任务快照：jobId 用于轮询，status 为 QUEUED、EMBEDDING、PUBLISHED 或 FAILED。
     * message 供页面显示，result 仅在发布成功后提供导入统计。
     */
    public record Job(String jobId,String status,String message,CustomKnowledgeImportResult result) { }
    /**
     * 缓存项持有已准备正文；工作线程更新 volatile 状态字段，使轮询线程看到最新快照。
     */
    private static final class Entry {
        final PreparedKnowledge prepared; volatile Instant expiresAt; volatile Job job;
        /**
         * 绑定已准备的知识和初始到期时间，任务在确认提交后才分配。
         */
        Entry(PreparedKnowledge prepared,Instant expiresAt) {this.prepared=prepared;this.expiresAt=expiresAt;}
    }
    private final Map<String,Entry> previews=new LinkedHashMap<>();
    private final CustomKnowledgeImportService importer;
    private final Clock clock;
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{
        Thread t=new Thread(r,"knowledge-import");t.setDaemon(true);return t;
    },new ThreadPoolExecutor.AbortPolicy());
    /**
     * 生产构造入口使用 UTC 时钟；委托可注入时钟的构造器便于测试到期行为。
     */
    @Autowired public KnowledgePreviewService(CustomKnowledgeImportService importer) {this(importer,Clock.systemUTC());}
    /**
     * 注入发布服务和时钟，测试可以推进时间而无需真实等待十五分钟。
     */
    public KnowledgePreviewService(CustomKnowledgeImportService importer,Clock clock) {this.importer=importer;this.clock=clock;}

    /**
     * 清理到期缓存后保存准备结果，最多 32 份，每份默认有效 900 秒；满额返回 429。
     */
    public synchronized Preview save(PreparedKnowledge prepared) {
        prune();
        if(previews.size()>=32)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"预览已满，请稍后重试；预览保留 15 分钟");
        String id=UUID.randomUUID().toString();Entry entry=new Entry(prepared,clock.instant().plusSeconds(900));previews.put(id,entry);
        return response(id,entry);
    }
    /**
     * 确认导入：同一预览的非失败任务复用原 jobId，避免重复点击产生重复发布。
     * 失败任务允许重试；队列满时撤销本次占位并返回 429，方便之后重新提交。
     */
    public synchronized Job submit(String id) {
        prune();Entry entry=previews.get(id);
        if(entry==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"预览已过期，请重新预览");
        // 对运行中和已成功任务做幂等复用，只有失败状态允许创建新尝试。
        if(entry.job!=null&&!entry.job.status().equals("FAILED"))return entry.job;
        Job queued=new Job(UUID.randomUUID().toString(),"QUEUED","等待后台生成向量",null);entry.job=queued;
        try {
            // 工作线程接管耗时向量化；HTTP 请求只等到任务入队，不等整份文件发布。
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
    /**
     * 按任务 ID 查询最近快照；预览到期被清理或进程重启后返回 404。
     */
    public synchronized Job job(String jobId) {
        prune();return previews.values().stream().map(e->e.job).filter(Objects::nonNull).filter(j->j.jobId().equals(jobId))
                .findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"任务已过期或应用已重启，请重新预览"));
    }
    /**
     * 仅删除已到期且未开始或已结束的缓存；排队和执行中的任务不能被时间清理打断。
     */
    private void prune() {
        previews.values().removeIf(e->clock.instant().isAfter(e.expiresAt)&&(e.job==null||Set.of("PUBLISHED","FAILED").contains(e.job.status())));
    }
    /**
     * 从同一准备结果构造完整块预览和最短、最长统计，复制元数据供只读展示。
     */
    private Preview response(String id,Entry entry) {
        var p=entry.prepared;
        var chunks=p.chunks().stream().map(d->new ChunkPreview(((Number)d.getMetadata().get("chunkIndex")).intValue(),d.getText().length(),
                d.getText(),d.getId(),Map.copyOf(d.getMetadata()))).toList();
        var stats=chunks.stream().mapToInt(ChunkPreview::characters).summaryStatistics();
        return new Preview(id,entry.expiresAt.toString(),p.source().sourceId(),p.source().sourceName(),p.source().sourceVersion(),p.source().fileName(),
                p.options().version(),p.extractedDocuments(),p.normalizedDocuments(),p.rawCharacters(),chunks.size(),p.totalCharacters(),
                stats.getMin(),stats.getMax(),p.warnings(),chunks);
    }
    /**
     * 容器关闭时停止接收新任务并有序关闭线程池；内存任务不具备重启恢复能力。
     */
    @PreDestroy public void close() {executor.shutdown();}
}
