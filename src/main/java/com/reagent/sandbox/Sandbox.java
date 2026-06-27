package com.reagent.sandbox;

/**
 * 工具沙箱 —— 在受控、隔离的环境里执行一段命令,并提供三重保障:
 * <ol>
 *   <li><b>硬杀</b>:到点用 OS 级手段强制终止(而非线程 interrupt)——死循环也停得下来;</li>
 *   <li><b>限额</b>:内存 / CPU 上限——失控代码拖不垮主服务;</li>
 *   <li><b>隔离</b>(Docker 实现):独立文件系统 + 断网——{@code rm -rf} 删不到真实数据、
 *       偷不走 {@code DEEPSEEK_API_KEY}、连不上外网。</li>
 * </ol>
 *
 * <p>这是把工具执行从“进程内 {@code Tool.execute}”升级为“隔离子进程 / 容器”的关键一层。
 * {@link com.reagent.tool.ToolExecutor} 那层的超时只能给虚拟线程发中断,模型代码不配合就杀不掉;
 * 真正的强制终止由本接口的实现用 {@code destroyForcibly()} / {@code docker kill} 兜底。</p>
 *
 * <p><b>并发约定</b>:{@link #run} 是<b>同步阻塞</b>的——跑完或被杀才返回。
 * 调用方跑在 JDK21 虚拟线程上,阻塞等待几乎零成本;同一轮多工具的并发由上层
 * {@code ToolExecutor.executeConcurrently} 负责,本层不关心并发,保持单次执行的纯粹与可测。</p>
 */
public interface Sandbox {

    /**
     * 在沙箱里执行 {@code spec} 描述的命令,同步等待其结束或被强杀。
     *
     * <p><b>契约</b>:无论命令正常退出、非零退出、超时被杀、还是沙箱内部出错,
     * 实现都必须返回一个 {@link SandboxResult},<b>不抛出业务异常</b>——
     * 让上层把它当作“观察结果”回给模型,由模型自行纠错(这是 agent 鲁棒性的来源)。</p>
     */
    SandboxResult run(SandboxSpec spec);

    /** 本沙箱的种类,用于日志 / 诊断 / 对照演示。 */
    SandboxType type();
}
