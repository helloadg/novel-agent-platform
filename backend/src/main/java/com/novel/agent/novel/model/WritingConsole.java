package com.novel.agent.novel.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 写作控制台 (Writing Console)
 * <p>
 * 由大纲师在三幕结构产出阶段初始化, 摘要员每章增量更新 status; 写手和审核员把它作为显式锚点,
 * 保证主线进度、暗线/伏笔追踪、剧情节奏不跑偏。
 * <p>
 * 持久化为 JSON 文本存入 t_novel_project.writing_console, 不单独建表。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_ABSENT)
public class WritingConsole implements Serializable {

    /** 当前阶段的人类可读描述, 例如 "第二段 · 发展" */
    private String phase;

    /**
     * 五段结构阶段索引 1-5 (开端/发展/中点/高潮/结局), 用于按章节号自动推断当前在哪个阶段;
     * null 时不自动推断, phase 字段即为最终判定
     */
    private Integer phaseIndex;

    /** 主线目标一句话, 写手在任何章节都不能偏离这条线 */
    private String mainLineGoal;

    /** 当前阶段应该推进到的主线状态 (供审核员判定进度是否到位) */
    private String phaseGoal;

    /** 当前章节的节奏标签: 紧张/舒缓/转折/爆发/铺垫 等 (作为默认值, 逐章覆盖见 rhythmByChapter) */
    private String rhythmTag;

    /** 按章节号覆盖节奏标签, key 为章节号字符串 (如 "1"/"7"), 优先于 rhythmTag */
    private Map<String, String> rhythmByChapter;

    /** 活跃暗线清单 */
    private List<Thread> activeThreads;

    /** 已埋设待收的伏笔 */
    private List<Foreshadow> pendingForeshadows;

    /** 本章不该做但未来章节要做的提醒 (默认值, 逐章覆盖见 avoidByChapter) */
    private List<String> avoidThisChapter;

    /** 按章节号指定禁止事项, key 为章节号字符串; 该章有效时优先于 avoidThisChapter */
    private Map<String, List<String>> avoidByChapter;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Thread implements Serializable {
        private String id;
        private String name;
        private String hint;
        private Integer setupChapter;
        private Integer resolveChapter;
        /** setup(已埋)/progress(进行中)/resolved(已收) — 摘要员每章更新 */
        private String status;
        /** 当前进展 (供写手知道"这条暗线进行到哪了") */
        private String currentProgress;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Foreshadow implements Serializable {
        private String id;
        private String description;
        private Integer setupChapter;
        private Integer resolveChapter;
        /** planted(已埋)/resolved(已收)/discarded(放弃) */
        private String status;
    }
}
