package com.example.cloudcustomerservice.knowledge;

public class KnowledgeUnavailableException extends RuntimeException {
    public KnowledgeUnavailableException() { super("Knowledge service unavailable"); }
}
