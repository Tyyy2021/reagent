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
import java.util.stream.Stream;

/**
 * 列目录工具:让 agent 能"看"文件系统结构。
 */
@Component
public class ListDirTool implements Tool {

    @Override
    public String name() {
        return "list_dir";
    }

    @Override
    public IdempotencyClass idempotency() {
        return IdempotencyClass.READ_ONLY;  // 只读:重放无害
    }

    @Override
    public String description() {
        return "列出本任务工作区内指定目录下的文件和子目录。用于在读取文件前先了解目录结构。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "要列出的目录路径(相对本任务工作区),如 . 或 tests"
                        )
                ),
                "required", List.of("path")
        );
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws IOException {
        String path = args.path("path").asText(".");
        Path dir = ctx.resolveInWorkspace(path);

        if (!Files.exists(dir)) {
            return "目录不存在:" + path;
        }
        if (!Files.isDirectory(dir)) {
            return path + " 不是目录。";
        }

        StringBuilder sb = new StringBuilder("目录 " + path + " 下的内容:\n");
        try (Stream<Path> entries = Files.list(dir)) {
            entries.sorted().forEach(p -> {
                String mark = Files.isDirectory(p) ? "[目录] " : "[文件] ";
                sb.append("  ").append(mark).append(p.getFileName()).append("\n");
            });
        }
        return sb.toString();
    }
}
