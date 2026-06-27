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
 * 读文件工具:让 agent 能读取文本文件内容。
 */
@Component
public class ReadFileTool implements Tool {

    /** 单次读取的字符上限,防止把超大文件塞爆上下文 */
    private static final int MAX_CHARS = 8000;

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public IdempotencyClass idempotency() {
        return IdempotencyClass.READ_ONLY;  // 只读:重放无害
    }

    @Override
    public String description() {
        return "读取本任务工作区内指定路径文本文件的内容。内容过长会被截断。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "要读取的文件路径(相对本任务工作区),如 solution.py 或 tests/test_solution.py"
                        )
                ),
                "required", List.of("path")
        );
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws IOException {
        String path = args.path("path").asText();
        if (path.isBlank()) {
            return "错误:必须提供 path 参数。";
        }

        Path file = ctx.resolveInWorkspace(path);
        if (!Files.exists(file)) {
            return "文件不存在:" + path;
        }
        if (Files.isDirectory(file)) {
            return path + " 是目录,不是文件。请改用 list_dir。";
        }

        String content = Files.readString(file);
        if (content.length() > MAX_CHARS) {
            content = content.substring(0, MAX_CHARS) + "\n...(内容过长,已截断)";
        }
        return "文件 " + path + " 的内容:\n" + content;
    }
}
