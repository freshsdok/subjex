package com.subjex.platform.contract.schedule;

/**
 * ScheduleActionType — closed action set a schedule may fire.
 * 调度到期后可执行的封闭动作集合。
 * <p>
 * Neither action may change system configuration, secrets, or provider settings, or run arbitrary code.
 * 这两种动作都不能改系统配置、密钥或提供方设置，也不能跑任意代码。
 */
public enum ScheduleActionType {
    /** Enqueue a deterministic platform_task via TaskMessagePort — 经 TaskMessagePort 入队一条确定性任务。 */
    ENQUEUE_TASK,
    /** Start a named classpath workflow already declared in the repo — 启动仓库中已声明的具名 classpath 工作流。 */
    START_WORKFLOW
}
