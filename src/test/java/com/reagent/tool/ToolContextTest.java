package com.reagent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ToolContext#resolveInWorkspace(String)} 的<b>遏制</b>验证 —— 纯逻辑、免费、可重复。
 *
 * <p>重点压最值钱的那条安全线:进程内文件工具(read/list/write)给的路径,
 * 无论用 {@code ..} 上爬还是塞绝对路径,都<b>逃不出本任务工作区</b>——
 * 这是把文件读写也锁进沙箱"爆炸半径"的关键。</p>
 */
class ToolContextTest {

    private ToolContext ctx(Path workspace) {
        return new ToolContext("test-task", workspace);
    }

    @Test
    void 普通相对路径_解析到工作区内(@TempDir Path ws) {
        Path r = ctx(ws).resolveInWorkspace("solution.py");

        assertEquals(ws.resolve("solution.py"), r);
        assertTrue(r.startsWith(ws), "结果必须在工作区内");
    }

    @Test
    void 嵌套子目录_在区内(@TempDir Path ws) {
        Path r = ctx(ws).resolveInWorkspace("tests/test_solution.py");

        assertEquals(ws.resolve("tests/test_solution.py"), r);
        assertTrue(r.startsWith(ws));
    }

    @Test
    void 点号_解析为工作区自身(@TempDir Path ws) {
        Path r = ctx(ws).resolveInWorkspace(".");

        assertEquals(ws, r);
        assertTrue(r.startsWith(ws));
    }

    @Test
    void 内部dotdot折叠后仍在区内_放行(@TempDir Path ws) {
        // a/../b.py 规范化后是 b.py,没逃出工作区,应放行
        Path r = ctx(ws).resolveInWorkspace("a/../b.py");

        assertEquals(ws.resolve("b.py"), r);
        assertTrue(r.startsWith(ws));
    }

    @Test
    void dotdot上爬越界_被拒(@TempDir Path ws) {
        assertThrows(WorkspaceEscapeException.class,
                () -> ctx(ws).resolveInWorkspace("../../etc/passwd"));
    }

    @Test
    void 单级dotdot爬到父目录_被拒(@TempDir Path ws) {
        // 父目录虽不是 /etc,但已不属本任务工作区,照样拒
        assertThrows(WorkspaceEscapeException.class,
                () -> ctx(ws).resolveInWorkspace("../sibling.txt"));
    }

    @Test
    void 绝对路径_被拒(@TempDir Path ws) {
        // resolve 遇绝对路径会直接采用它,normalize 后 startsWith 校验失败 -> 拒
        assertThrows(WorkspaceEscapeException.class,
                () -> ctx(ws).resolveInWorkspace("/etc/passwd"));
    }
}
