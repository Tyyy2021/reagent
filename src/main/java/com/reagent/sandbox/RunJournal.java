package com.reagent.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 运行日志(journal)—— exactly-once 的 <b>L3</b> 落点,只服务于 {@code run_command} 这类有副作用的工具。
 *
 * <p><b>解决什么</b>:非幂等命令崩在"已开始执行、但还没把结果记进账本"的窗口里时,恢复方无法分辨
 * 它<b>究竟跑完没有</b>,只能保守地一律上报 in-doubt(偏保守、召回低)。本类让<b>沙箱自己</b>把
 * "命令已完成(退出码=N)"作为脚本的<b>最后一步</b>原子写进 workspace 下的一个小文件;恢复时
 * IN_PROGRESS 的非幂等命令<b>先查这个文件</b>:有→命令其实跑完了→对账标 DONE、不重放;无→真崩在
 * 执行中途→上报 in-doubt。把危险窗口从 {@code [开始执行, 记完账]} 缩到只剩 {@code [命令执行中途崩]}。</p>
 *
 * <p><b>为什么写在沙箱里(shell 尾部)而非 Java 等命令返回后再写</b>:若由 Java 在命令返回后写,
 * "命令已结束 → Java 还没来得及写 journal" 之间又是一个崩溃窗口;让沙箱脚本把它当最后一步写,
 * 才能保证"journal 存在 ⟺ 命令确已执行完毕"。原子性靠"写临时文件 + {@code mv} rename"
 * (同文件系统下 rename 原子),避免读到写了一半的半截文件。</p>
 *
 * <p><b>为什么只记退出码、不记输出</b>:输出在崩溃后本就没留存,记完整输出还要在 shell 里另开
 * tee/重定向、丧失 sh 可移植性;对账只需"命令完成"这个事实 + 退出码即可把账本从"存疑"救回"完成",
 * 诚实标注"输出未留存"足矣。</p>
 *
 * <p>这里是 journal 文件布局的<b>单一真相源</b>:两个沙箱({@link SubprocessSandbox}/{@link DockerSandbox})
 * 写、{@code AgentRunner} 恢复时读,路径约定都从这里取,避免三处各写一份不一致。</p>
 */
public final class RunJournal {

    private RunJournal() {
    }

    /** journal 目录(相对 workspace 根);点开头,尽量不干扰模型在工作区里 ls 出来的产物。 */
    public static final String REL_DIR = ".reagent/journal";

    /** 注入沙箱的幂等 key 环境变量名 —— 供未来"外部副作用 + 下游去重"的工具(类③)读取。 */
    public static final String ENV_KEY = "REAGENT_IDEMPOTENCY_KEY";

    /** 某次调用的完成记录在<b>宿主</b>上的路径(subprocess 直接落在此;docker 经 /workspace 绑定挂载也落在此)。 */
    public static Path file(Path workspace, String callId) {
        return workspace.resolve(REL_DIR).resolve(callId);
    }

    /**
     * 读这次调用的完成记录:有→返回退出码字符串(命令确已跑完),无/不可读→空(当作没跑完)。
     * 容错优先:任何读异常都按"无记录"处理,宁可保守上报 in-doubt,绝不误判成已完成。
     */
    public static Optional<String> completion(Path workspace, String callId) {
        if (!validKey(callId)) {
            return Optional.empty();
        }
        Path f = file(workspace, callId);
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        try {
            String s = Files.readString(f, StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? Optional.empty() : Optional.of(s);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** callId 能否安全嵌进单引号 shell 路径(模型给的 tool_call_id 形如 {@code call_0_xxx},这里只做防御性校验)。 */
    public static boolean validKey(String callId) {
        return callId != null && callId.matches("[A-Za-z0-9_.:-]+");
    }

    /**
     * 把<b>用户命令</b>包成"执行 + 记完成 journal"的一段脚本:
     * <pre>
     *   ( &lt;command&gt; )           # 子shell 执行:命令里若有 exit 只退子shell,不掀翻下面的记账 epilogue
     *   __reagent_rc=$?           # 抓命令真实退出码
     *   mkdir -p dir; printf rc &gt; tmp &amp;&amp; mv -f tmp fin   # 原子写完成记录(临时文件 + rename)
     *   exit "$__reagent_rc"      # 用命令真实退出码退出,沙箱观察到的 exitCode 不被记账步骤污染
     * </pre>
     * 全程容错({@code 2>/dev/null}、{@code &&}):journal 写失败不影响命令本身的退出码与输出。
     * 无 key 时<b>原样返回命令</b>(= 关闭 journal,行为与改造前完全一致)。
     *
     * @param command 用户命令(模型给的 shell 串)
     * @param baseDir journal 根目录"<b>在沙箱内看到</b>"的基准:subprocess 用宿主 workspace 绝对路径,
     *                docker 用容器内挂载点(/workspace);二者最终都落到宿主同一个文件。
     * @param callId  本次调用 id(= idempotencyKey);非法/为空时不记 journal。
     */
    public static String wrap(String command, String baseDir, String callId) {
        if (!validKey(callId)) {
            return command;
        }
        String dir = baseDir + "/" + REL_DIR;
        String tmp = dir + "/" + callId + ".tmp";
        String fin = dir + "/" + callId;
        // 子shell ( ) 隔离命令里的 exit;换行分隔避免与命令尾部 ';' 拼出 ';;';__reagent_rc 命名避免撞用户变量。
        return "( " + command + " )"
                + "\n__reagent_rc=$?"
                + "\nmkdir -p '" + dir + "' 2>/dev/null"
                + "\nprintf '%s' \"$__reagent_rc\" > '" + tmp + "' 2>/dev/null && mv -f '" + tmp + "' '" + fin + "' 2>/dev/null"
                + "\nexit \"$__reagent_rc\"";
    }
}
