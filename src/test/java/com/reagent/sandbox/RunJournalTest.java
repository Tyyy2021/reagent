package com.reagent.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RunJournal} 纯单测 —— L3 对账的两端:
 * <ol>
 *   <li>{@code completion}:恢复时读完成记录,据此把 IN_PROGRESS 判成 DONE(有记录)或 in-doubt(无记录);</li>
 *   <li>{@code appendSnippet}:注入沙箱的 shell 片段形状(原子写 + 用命令真实退出码退出);</li>
 *   <li>{@code validKey}:fail-safe 校验,挡住不安全/为空的 key。</li>
 * </ol>
 */
class RunJournalTest {

    @Test
    void completion_有完成记录时读到退出码(@TempDir Path ws) throws Exception {
        Path f = RunJournal.file(ws, "call_1");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "0\n");
        assertEquals(Optional.of("0"), RunJournal.completion(ws, "call_1"));
    }

    @Test
    void completion_无记录时为空(@TempDir Path ws) {
        assertTrue(RunJournal.completion(ws, "call_missing").isEmpty());
    }

    @Test
    void completion_空内容保守当作没跑完(@TempDir Path ws) throws Exception {
        Path f = RunJournal.file(ws, "call_blank");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "   ");
        assertTrue(RunJournal.completion(ws, "call_blank").isEmpty(), "半截/空内容应保守当作没跑完");
    }

    @Test
    void wrap_有key时子shell执行并原子写真实退出码() {
        String s = RunJournal.wrap("echo hi", "/ws", "call_9");
        assertTrue(s.contains("( echo hi )"), "命令应在子shell 里执行,隔离其中的 exit: " + s);
        assertTrue(s.contains("__reagent_rc=$?"), s);
        assertTrue(s.contains("/ws/" + RunJournal.REL_DIR + "/call_9"), "应写到约定的 journal 路径");
        assertTrue(s.contains("mv -f"), "原子性靠临时文件 + rename");
        assertTrue(s.contains("exit \"$__reagent_rc\""), "必须用命令真实退出码退出,不被写 journal 步骤污染");
    }

    @Test
    void wrap_无key时原样返回命令_等于关闭journal() {
        assertEquals("echo hi", RunJournal.wrap("echo hi", "/ws", null));
        assertEquals("echo hi", RunJournal.wrap("echo hi", "/ws", ""));
    }

    @Test
    void validKey_只放行安全字符() {
        assertTrue(RunJournal.validKey("call_0_aZ.9:-"));
        assertFalse(RunJournal.validKey(null));
        assertFalse(RunJournal.validKey(""));
        assertFalse(RunJournal.validKey("bad'quote"));
        assertFalse(RunJournal.validKey("has space"));
    }
}
