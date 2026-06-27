package com.reagent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.reagent.tool.IdempotencyClass;
import com.reagent.tool.Tool;
import com.reagent.tool.ToolContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 写文件工具:让 agent 能在本任务工作区内创建/修改文本文件 —— Coding Agent 闭环的另一只"手"。
 *
 * <p>写入路径经 {@link ToolContext#resolveInWorkspace(String)} 遏制在工作区内,
 * 越界(绝对路径 / {@code ..} 上爬)直接拒;父目录不存在会自动创建;同名文件直接覆盖。
 * 配合 {@code run_command}(跑测试)与 {@code read_file}(读回结果),agent 就能
 * 写代码 → 写测试 → 跑测试 → 看失败 → 改代码 地自包含闭环迭代。</p>
 */
@Component
public class WriteFileTool implements Tool {

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public IdempotencyClass idempotency() {
        // 覆盖写同 path 同 content = 同末态,重放安全(不是 append)
        return IdempotencyClass.IDEMPOTENT;
    }

    @Override
    public String description() {
        return "把文本内容写入本任务工作区内的指定文件(已存在则覆盖)。父目录不存在会自动创建。"
                + "用于创建/修改源码、写测试、保存脚本等。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "要写入的文件路径(相对本任务工作区),如 solution.py 或 tests/test_solution.py"
                        ),
                        "content", Map.of(
                                "type", "string",
                                "description", "要写入文件的完整文本内容(会覆盖原有内容)"
                        )
                ),
                "required", List.of("path", "content")
        );
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws IOException {
        String path = args.path("path").asText();
        if (path.isBlank()) {
            return "错误:必须提供 path 参数。";
        }
        if (!args.has("content")) {
            return "错误:必须提供 content 参数。";
        }
        String content = args.path("content").asText();

        Path file = ctx.resolveInWorkspace(path);
        if (Files.isDirectory(file)) {
            return path + " 是一个目录,不能作为文件写入。";
        }

        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content);
        return "已写入文件 " + path + "(" + content.length() + " 个字符)。";
    }
}
