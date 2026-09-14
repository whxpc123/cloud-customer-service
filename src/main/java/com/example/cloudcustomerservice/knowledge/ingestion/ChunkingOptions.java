package com.example.cloudcustomerservice.knowledge.ingestion;

/**
 * 第八章可预览的切分参数，Token 数按 CL100K_BASE 估计，不等于 Qwen 计费数。
 *
 * @param chunkSize 目标块大小，只允许 200、500、1000 三档；附加标题可能增大最终文本
 * @param pdfTopLines 每页删除的页首文本行数，0～5，默认不删
 * @param pdfBottomLines 每页删除的页尾文本行数，0～5，默认不删
 */
public record ChunkingOptions(int chunkSize, int pdfTopLines, int pdfBottomLines) {
    /**
     * 在构造时拒绝未支持的切分档位和过大的 PDF 清理行数，所有入口共用这套约束。
     */
    public ChunkingOptions {
        if (chunkSize != 200 && chunkSize != 500 && chunkSize != 1000)
            throw new IllegalArgumentException("切分大小仅支持 200、500 或 1000 Token");
        if (pdfTopLines < 0 || pdfTopLines > 5 || pdfBottomLines < 0 || pdfBottomLines > 5)
            throw new IllegalArgumentException("PDF 页首、页尾清理行数须为 0–5");
    }
    /**
     * 默认 500 Token，保留 PDF 页首页尾；清理须由用户预览核对后选择。
     */
    public static ChunkingOptions defaults() { return new ChunkingOptions(500, 0, 0); }
    /**
     * 将切分档位与清理参数编码到版本字符串，参与知识块 ID 计算以区分处理策略。
     */
    public String version() { return "token-" + chunkSize + "-v1-top" + pdfTopLines + "-bottom" + pdfBottomLines; }
}
