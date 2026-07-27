package com.reagent.profile;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Trusted profile definitions loaded from {@code reagent.profiles}. */
@ConfigurationProperties(prefix = "reagent.profiles")
public class AgentProfileProperties {

    private String defaultId = "coding";
    private int maxSnapshotBytes = 262_144;
    private Map<String, Profile> definitions = new LinkedHashMap<>();

    public String getDefaultId() { return defaultId; }
    public void setDefaultId(String defaultId) { this.defaultId = defaultId; }

    public int getMaxSnapshotBytes() { return maxSnapshotBytes; }
    public void setMaxSnapshotBytes(int maxSnapshotBytes) { this.maxSnapshotBytes = maxSnapshotBytes; }

    public Map<String, Profile> getDefinitions() { return definitions; }
    public void setDefinitions(Map<String, Profile> definitions) { this.definitions = definitions; }

    public static class Profile {
        private String version;
        private String systemPrompt;
        private List<String> toolNames = new ArrayList<>();
        private String knowledgeBaseId;
        private String knowledgeIndexVersion;
        private List<String> mcpServerIds = new ArrayList<>();

        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }

        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

        public List<String> getToolNames() { return toolNames; }
        public void setToolNames(List<String> toolNames) { this.toolNames = toolNames; }

        public String getKnowledgeBaseId() { return knowledgeBaseId; }
        public void setKnowledgeBaseId(String knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }

        public String getKnowledgeIndexVersion() { return knowledgeIndexVersion; }
        public void setKnowledgeIndexVersion(String knowledgeIndexVersion) {
            this.knowledgeIndexVersion = knowledgeIndexVersion;
        }

        public List<String> getMcpServerIds() { return mcpServerIds; }
        public void setMcpServerIds(List<String> mcpServerIds) { this.mcpServerIds = mcpServerIds; }
    }
}
