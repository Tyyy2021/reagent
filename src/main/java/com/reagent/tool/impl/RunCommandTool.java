package com.reagent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.reagent.sandbox.Sandbox;
import com.reagent.sandbox.SandboxProperties;
import com.reagent.sandbox.SandboxResult;
import com.reagent.sandbox.SandboxSpec;
import com.reagent.tool.IdempotencyClass;
import com.reagent.tool.Tool;
import com.reagent.tool.ToolContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 在<b>沙箱</b>里执行一条 shell 命令的工具 —— Coding Agent 的核心“手”。
 *
 * <p>命令在本任务的独立工作目录中、经 {@link Sandbox} 隔离执行:有硬超时(到点 OS 强杀,
 * 死循环也停得下来)、资源限额、(Docker 模式下)文件 / 网络隔离。无论命令成功、失败、
 * 还是超时被杀,都把“观察结果”原样回给模型,由模型决定下一步(这是 agent 鲁棒性的来源)。</p>
 */
@Component
public class RunCommandTool implements Tool {

    private final Sandbox sandbox;
    private final SandboxProperties props;

    public RunCommandTool(Sandbox sandbox, SandboxProperties props) {
        this.sandbox = sandbox;
        this.props = props;
    }

    @Override
    public String name() {
        return "run_command";
    }

    @Override
    public IdempotencyClass idempotency() {
        // 任意 shell:可能 append / POST / push 等不可逆副作用 → 崩在 in-doubt 不盲目重放(L3 对账 journal,无则上报)
        return IdempotencyClass.SIDE_EFFECTFUL;
    }

    @Override
    public String description() {
        return "在隔离沙箱中执行一条 shell 命令,返回其退出码与输出(stdout/stderr)。"
                + "命令运行在本任务的独立工作目录下,有超时与资源限制。"
                + "适合运行脚本、查看命令结果、执行构建或测试等。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "command", Map.of(
                                "type", "string",
                                "description", "要执行的 shell 命令,如 'ls -la' 或 'python3 script.py'"
                        )
                ),
                "required", List.of("command")
        );
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) {
        String command = args.path("command").asText();
        if (command.isBlank()) {
            return "错误:必须提供 command 参数。";
        }

        // 默认资源/超时取自配置,命令与工作目录(本任务 workspace)按本次调用填入
        // L3:下推本次调用的 idempotencyKey(= tool_call_id),沙箱据此写完成 journal + 注入 env,供崩溃恢复对账。
        SandboxSpec spec = SandboxSpec.fromDefaults(props)
                .command(command)
                .workingDir(ctx.workspaceDir())
                .idempotencyKey(ctx.idempotencyKey())
                .build();

        SandboxResult r = sandbox.run(spec);
        return format(r);
    }

    /** 把沙箱结果整理成给模型看的文本:状态在前、输出在后,区分 stdout / stderr。 */
    private String format(SandboxResult r) {
        // 基础设施故障:命令根本没跑起来(daemon/镜像/工作目录问题),提示模型别改命令重试
        if (r.startupFailed()) {
            return "沙箱基础设施错误(非命令本身的问题,通常无需修改命令重试):" + r.stderr();
        }
        StringBuilder sb = new StringBuilder();
        if (r.killed()) {
            sb.append("命令因超时被强制终止(exit=").append(r.exitCode()).append(")。\n");
        } else {
            sb.append("命令执行完毕,退出码 ").append(r.exitCode())
              .append(r.success() ? "(成功)。\n" : "(非零,可能失败)。\n");
        }
        if (r.outputTruncated()) {
            sb.append("(注意:输出过长,已被截断)\n");
        }
        sb.append("[stdout]\n").append(orEmpty(r.stdout()));
        if (r.stderr() != null && !r.stderr().isBlank()) {
            sb.append("\n[stderr]\n").append(r.stderr());
        }
        return sb.toString();
    }

    private static String orEmpty(String s) {
        return (s == null || s.isEmpty()) ? "(无输出)" : s;
    }
}
