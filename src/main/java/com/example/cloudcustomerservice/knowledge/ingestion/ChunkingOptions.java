package com.example.cloudcustomerservice.knowledge.ingestion;

public record ChunkingOptions(int chunkSize, int pdfTopLines, int pdfBottomLines) {
    public ChunkingOptions {
        if (chunkSize != 200 && chunkSize != 500 && chunkSize != 1000)
            throw new IllegalArgumentException("切分大小仅支持 200、500 或 1000 Token");
        if (pdfTopLines < 0 || pdfTopLines > 5 || pdfBottomLines < 0 || pdfBottomLines > 5)
            throw new IllegalArgumentException("PDF 页首、页尾清理行数须为 0–5");
    }
    public static ChunkingOptions defaults() { return new ChunkingOptions(500, 0, 0); }
    public String version() { return "token-" + chunkSize + "-v1-top" + pdfTopLines + "-bottom" + pdfBottomLines; }
}
