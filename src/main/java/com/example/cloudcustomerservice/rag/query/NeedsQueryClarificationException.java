package com.example.cloudcustomerservice.rag.query;

/** 当前指代无法可靠补全，证据门在最终模型之前要求用户补充，不能冒充“数据库无资料”。 */
public final class NeedsQueryClarificationException extends RuntimeException { }
