package com.example.cloudcustomerservice.rag.query;

/** 本地实验输入；rewrite/compare 仅显式选择时启用，不接受客户端指定租户或过滤条件。 */
public record QueryTransformationRequest(String question, boolean rewrite, boolean compare) { }
