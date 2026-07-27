package com.reagent.profile;

import com.reagent.tool.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory binding of a persisted allowlist to exact current runtime tools. */
public final class TaskToolCatalog {

    private final Map<String, Tool> runtimeTools;
    private final Map<String, ToolSnapshot> snapshots;
    private final List<Map<String, Object>> openAiSpec;

    TaskToolCatalog(TaskProfileSnapshot profile, Map<String, Tool> runtimeTools) {
        this.runtimeTools = Map.copyOf(runtimeTools);
        Map<String, ToolSnapshot> frozen = new LinkedHashMap<>();
        List<Map<String, Object>> specs = new ArrayList<>();
        for (ToolSnapshot tool : profile.tools()) {
            frozen.put(tool.name(), tool);

            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.put("parameters", tool.parameterSchema());

            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("type", "function");
            spec.put("function", Collections.unmodifiableMap(function));
            specs.add(Collections.unmodifiableMap(spec));
        }
        this.snapshots = Collections.unmodifiableMap(frozen);
        this.openAiSpec = List.copyOf(specs);
    }

    public Tool get(String name) {
        return runtimeTools.get(name);
    }

    public ToolSnapshot snapshot(String name) {
        return snapshots.get(name);
    }

    public List<Map<String, Object>> toOpenAiSpec() {
        return openAiSpec;
    }
}
