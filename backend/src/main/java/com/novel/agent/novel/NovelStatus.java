package com.novel.agent.novel;

/**
 * 小说项目状态机
 * <p>
 * DRAFT → OUTLINING → OUTLINE_READY →（用户确认大纲）→ WRITING → COMPLETED
 * 任一执行阶段失败进入 FAILED, 允许重新触发并从失败章节继续。
 */
public enum NovelStatus {

    /** 已创建, 尚未开始生成 */
    DRAFT,
    /** 阶段一: 设定师 + 大纲师正在生成 */
    OUTLINING,
    /** 阶段一完成, 等待用户确认/修改大纲 */
    OUTLINE_READY,
    /** 阶段二: 逐章写作中 */
    WRITING,
    /** 全部章节完成 */
    COMPLETED,
    /** 执行失败 (error_msg 记录原因, 已生成章节保留) */
    FAILED
}
