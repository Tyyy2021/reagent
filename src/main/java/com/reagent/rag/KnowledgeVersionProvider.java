package com.reagent.rag;

/** Resolves the current concrete index version for creation of a new task snapshot. */
@FunctionalInterface
public interface KnowledgeVersionProvider {

    String requireActiveVersion(String knowledgeBaseId);
}
