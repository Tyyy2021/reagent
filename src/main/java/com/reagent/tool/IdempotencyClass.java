package com.reagent.tool;

/**
 * 工具的幂等等级 —— 决定崩溃恢复时,一次停在 in-doubt(IN_PROGRESS 未收尾)的调用能不能安全重放。
 *
 * <ul>
 *   <li><b>READ_ONLY</b>     : 不改外部状态(read_file / list_dir / sleep_ms)。重放纯无害 → in-doubt 直接重跑。</li>
 *   <li><b>IDEMPOTENT</b>    : 会改状态,但<b>同输入重放 = 同末态</b>(write_file 覆盖写同 path 同 content)。
 *                             in-doubt 也能安全重跑。</li>
 *   <li><b>SIDE_EFFECTFUL</b>: 任意 / 不可逆副作用(run_command 跑任意 shell:append、POST、push…)。
 *                             重放危险 → in-doubt 时<b>不盲目重跑</b>:先对账 journal(L3),有完成记录就复用、
 *                             无则标 IN_DOUBT 上报,交模型核对 / 重发。</li>
 * </ul>
 *
 * <p>这个等级只在<b>崩溃恢复</b>那条路上起作用——它决定如何处置一次 IN_PROGRESS 的"在途"调用;
 * 正常执行(PENDING→DONE)不受影响。</p>
 */
public enum IdempotencyClass {
    READ_ONLY,
    IDEMPOTENT,
    SIDE_EFFECTFUL
}
