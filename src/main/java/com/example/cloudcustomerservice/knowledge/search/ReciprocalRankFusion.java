package com.example.cloudcustomerservice.knowledge.search;

import java.util.*;
import org.springframework.ai.document.Document;

/** 跨路按名次融合，绝不相加向量/全文原分数；精确匹配以独立业务优先级进入候选。 */
public final class ReciprocalRankFusion {
    private ReciprocalRankFusion() { }
    public static List<Document> fuse(List<Document> vector,List<Document> keyword,List<Document> exact,int limit) {
        if(limit<1 || limit>24)throw new IllegalArgumentException("Invalid fusion limit");
        var entries=new LinkedHashMap<String,Entry>();
        add(entries,vector,"VECTOR");add(entries,keyword,"KEYWORD");add(entries,exact,"EXACT");
        return entries.values().stream().sorted(Comparator.comparing((Entry e)->!e.sources.contains("EXACT"))
                .thenComparing(Comparator.comparingDouble(Entry::rrf).reversed()).thenComparing(e->e.doc.getId()))
                .limit(limit).map(Entry::document).toList();
    }
    /** 同一路重复 ID 只计算第一次名次，避免重复块人为增加融合分。 */
    private static void add(Map<String,Entry> all,List<Document> docs,String route) {
        var seen=new HashSet<String>();
        for(int i=0;i<docs.size();i++) {
            Document d=docs.get(i);if(!seen.add(d.getId()))continue;
            Entry e=all.computeIfAbsent(d.getId(),key->new Entry(d));e.sources.add(route);
            if(route.equals("VECTOR")){e.vectorRank=i+1;e.vectorScore=d.getScore();}
            if(route.equals("KEYWORD")){e.keywordRank=i+1;e.keywordScore=d.getScore();}
        }
    }
    private static final class Entry {
        final Document doc; final List<String> sources=new ArrayList<>();
        Integer vectorRank,keywordRank;Double vectorScore,keywordScore;
        Entry(Document doc){this.doc=doc;}
        double rrf(){return (vectorRank==null?0:1.0/(60+vectorRank))+(keywordRank==null?0:1.0/(60+keywordRank));}
        Document document(){
            var m=new HashMap<>(doc.getMetadata());
            // 一个命中可来自三路；空分数不写入 Document 元数据，响应快照显式保留空值。
            m.put("retrievalSources",List.copyOf(sources));m.put("rrfScore",rrf());m.put("exactMatch",sources.contains("EXACT"));
            if(vectorRank!=null){m.put("vectorRank",vectorRank);m.put("vectorScore",vectorScore);}
            if(keywordRank!=null){m.put("keywordRank",keywordRank);m.put("keywordScore",keywordScore);}
            return doc.mutate().metadata(m).score(rrf()).build();
        }
    }
}
