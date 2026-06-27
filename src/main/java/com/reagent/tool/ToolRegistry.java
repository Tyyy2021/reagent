package com.reagent.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表。
 * 利用 Spring 的能力:构造时注入所有 Tool 类型的 Bean,自动完成注册。
 * 新增一个工具,只要写个 @Component 实现 Tool 即可,这里零改动。
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> toolBeans) {
        for (Tool t : toolBeans) {
            tools.put(t.name(), t);
        }
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    /**
     * 把所有工具转成 OpenAI function-calling 规格,供发请求时带给模型。
     * 形如:[{ "type":"function", "function":{ "name":..., "description":..., "parameters":<schema> } }]
     */
    public List<Map<String, Object>> toOpenAiSpec() {
        List<Map<String, Object>> specs = new ArrayList<>();
        for (Tool t : tools.values()) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", t.name());
            function.put("description", t.description());
            function.put("parameters", t.parameterSchema());

            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("type", "function");
            spec.put("function", function);
            specs.add(spec);
        }
        return specs;
    }
}
