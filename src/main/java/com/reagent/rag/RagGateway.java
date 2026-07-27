package com.reagent.rag;

/** Trusted Java boundary for the internal Python RAG service. */
public interface RagGateway extends KnowledgeVersionProvider {

    RagSearchResponse search(RagSearchRequest request);
}
