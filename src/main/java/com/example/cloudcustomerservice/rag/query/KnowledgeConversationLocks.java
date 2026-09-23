package com.example.cloudcustomerservice.rag.query;

/** 有界分段锁：同一知识会话的发送、清空与实验快照串行；不随会话数量无限增长。 */
public final class KnowledgeConversationLocks {
    private static final Object[] LOCKS = java.util.stream.IntStream.range(0, 256).mapToObj(i -> new Object()).toArray();
    /** 纯静态工具类，不允许实例化。 */
    private KnowledgeConversationLocks() { }
    /** 固定数量锁限制内存；哈希碰撞只会让少量不同会话串行，不会混用历史。 */
    public static Object forKey(String key) { return LOCKS[Math.floorMod(key.hashCode(), LOCKS.length)]; }
}
