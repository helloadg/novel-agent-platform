package com.novel.agent.novel;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 小说写作协同图的状态
 * <p>
 * 注意: 检查点由 PostgresSaverV2 持久化, 默认走 Java 原生序列化, 因此所有值必须 Serializable。
 * 为避免序列化踩坑, 复杂结构统一用 JSON 文本 (String) 承载, 不放入 langchain4j 的 ChatMessage 等类型。
 */
public class NovelState extends AgentState {

    /** 项目主键 */
    public static final String PROJECT_ID = "projectId";
    /** 题材枚举名 (NovelGenre.name()) */
    public static final String GENRE = "genre";
    /** 用户输入的初始想法或大纲 */
    public static final String USER_IDEA = "userIdea";
    /** 世界观 + 规则体系 (Markdown 文本) */
    public static final String WORLDVIEW = "worldview";
    /** 主要人物设定 (Markdown 文本) */
    public static final String CHARACTERS = "characters";
    /** 全局架构 + 逐章大纲 (Markdown 文本) */
    public static final String OUTLINE = "outline";
    /** 全局故事架构 (L0, 分卷规划/主线/结局锚点), 后续每卷细纲都以此为准 */
    public static final String GLOBAL_ARCH = "globalArch";
    /** 当前已细化的卷号 (从 1 开始) */
    public static final String VOLUME_INDEX = "volumeIndex";
    /** 分章细纲已细化到的最大章号: 写到这里之后需要先生成下一卷细纲 */
    public static final String OUTLINE_HORIZON = "outlineHorizon";
    /** 写作控制台 JSON (主线阶段/暗线/伏笔/节奏/禁止项) */
    public static final String WRITING_CONSOLE = "writingConsole";
    /** 全书总章数 */
    public static final String TOTAL_CHAPTERS = "totalChapters";
    /** 当前正在写作的章号, 从 1 开始 */
    public static final String CURRENT_CHAPTER = "currentChapter";
    /** 当前进度的人类可读描述, 供前端展示 */
    public static final String STAGE = "stage";
    /** 当前章标题 */
    public static final String CHAPTER_TITLE = "chapterTitle";
    /** 当前章大纲 */
    public static final String CHAPTER_OUTLINE = "chapterOutline";
    /** 当前章正文草稿 */
    public static final String DRAFT = "draft";
    /** 重写前的上一稿正文: 写手重写前备份, 供审核员对比"是否删了情节/篇幅缩水" */
    public static final String PREVIOUS_DRAFT = "previousDraft";
    /** 审核结果 JSON (ReviewResult 序列化) */
    public static final String REVIEW = "review";
    /** 审核判定是否需要退回重写 */
    public static final String REWRITE_NEEDED = "rewriteNeeded";
    /** 重写轮次已耗尽仍未通过, 需要人工处理 (图终止, 项目标记 FAILED) */
    public static final String NEEDS_REVIEW = "needsReview";
    /** 当前章已发生的重写轮次 */
    public static final String REWRITE_ROUNDS = "rewriteRounds";
    /** 人工干预标记: 由后端 endpoints 在中断点上设置, 决定 AWAIT_MANUAL 的后续路由 */
    public static final String MANUAL_RERUN = "manualRerun";
    /** 人工强行放行: 跳过审核继续润色/摘要 */
    public static final String MANUAL_APPROVED = "manualApproved";
    /** 人工给写手的额外提示词 (重写/放行时注入 writer prompt, 下一章自动清空) */
    public static final String MANUAL_HINT = "manualHint";
    /** 人工要求完全重写: 不注入上一稿, 允许推翻原稿结构 (仅本次重写生效, 下一章自动清空) */
    public static final String MANUAL_FULL_REWRITE = "manualFullRewrite";
    /** 世界观人工复审: 人工要求重新生成世界观/人物设定 */
    public static final String WORLD_RERUN = "worldRerun";
    /** 世界观人工复审: 人工给设定师的修改意见, 重生成时注入 prompt */
    public static final String WORLD_HINT = "worldHint";
    /** 分卷大纲人工复审: 人工要求重新生成本卷分章大纲 */
    public static final String VOLUME_RERUN = "volumeRerun";
    /** 分卷大纲人工复审: 人工给分卷大纲师的修改意见, 重生成时注入 prompt */
    public static final String VOLUME_HINT = "volumeHint";
    /** 滚动剧情总纲 (摘要节点维护) */
    public static final String ROLLING_SUMMARY = "rollingSummary";
    /** 已完成章节的摘要列表 (appender 累加语义) */
    public static final String CHAPTER_SUMMARIES = "chapterSummaries";
    /** 主要人物当前状态快照 (摘要节点维护) */
    public static final String CHARACTER_STATES = "characterStates";
    /** 全部章节是否已写完 */
    public static final String DONE = "done";

    /**
     * 状态通道定义: 只有需要"累加"语义的字段用 appender reducer,
     * 其余 key 走默认覆盖语义 (节点返回的新值直接替换旧值)
     */
    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            CHAPTER_SUMMARIES, Channels.appender(ArrayList::new));

    public NovelState(Map<String, Object> data) {
        super(data);
    }

    private String str(String key) {
        return this.<String>value(key).orElse("");
    }

    private int intVal(String key, int defaultValue) {
        return this.<Integer>value(key).orElse(defaultValue);
    }

    private boolean boolVal(String key, boolean defaultValue) {
        return this.<Boolean>value(key).orElse(defaultValue);
    }

    public Long projectId() {
        return this.<Long>value(PROJECT_ID).orElse(null);
    }

    public String genre() {
        return str(GENRE);
    }

    public String userIdea() {
        return str(USER_IDEA);
    }

    public String worldview() {
        return str(WORLDVIEW);
    }

    public String characters() {
        return str(CHARACTERS);
    }

    public String outline() {
        return str(OUTLINE);
    }

    /** 全局故事架构 (分卷规划/主线/结局锚点), 滚动生成各卷细纲时的唯一依据 */
    public String globalArch() {
        return str(GLOBAL_ARCH);
    }

    public int volumeIndex() {
        return intVal(VOLUME_INDEX, 1);
    }

    /**
     * 细纲已覆盖到的章号。
     * 缺省值取总章数: 老项目 (一次性生成了全书细纲) 不会触发卷切换逻辑。
     */
    public int outlineHorizon() {
        return intVal(OUTLINE_HORIZON, totalChapters());
    }

    /** 写作控制台 JSON (主线阶段/暗线清单/伏笔追踪/节奏标签) */
    public String writingConsole() {
        return str(WRITING_CONSOLE);
    }

    /** 当前阶段描述 (节点自己写入, 供进度展示) */
    public String stage() {
        return str(STAGE);
    }

    public int totalChapters() {
        return intVal(TOTAL_CHAPTERS, 3);
    }

    public int currentChapter() {
        return intVal(CURRENT_CHAPTER, 1);
    }

    public String chapterTitle() {
        return str(CHAPTER_TITLE);
    }

    public String chapterOutline() {
        return str(CHAPTER_OUTLINE);
    }

    public String draft() {
        return str(DRAFT);
    }

    /** 重写前的上一稿正文 (首次写作时为空) */
    public String previousDraft() {
        return str(PREVIOUS_DRAFT);
    }

    public String reviewJson() {
        return str(REVIEW);
    }

    /** 审核判定是否需要退回重写 (由审核节点写入) */
    public boolean rewriteNeeded() {
        return boolVal(REWRITE_NEEDED, false);
    }

    /** 重写轮次已耗尽仍未通过 —— 图在 AWAIT_MANUAL 中断, 等待人工干预 */
    public boolean needsReview() {
        return boolVal(NEEDS_REVIEW, false);
    }

    public int rewriteRounds() {
        return intVal(REWRITE_ROUNDS, 0);
    }

    /** 人工决定重写本章 (重置 rewriteRounds, 退回 chapter_writer) */
    public boolean manualRerun() {
        return boolVal(MANUAL_RERUN, false);
    }

    /** 人工决定放行 (跳过审核, 继续正常润色/摘要流程) */
    public boolean manualApproved() {
        return boolVal(MANUAL_APPROVED, false);
    }

    /** 人工给写手/润色编辑的额外提示词, 没有则返回空串 */
    public String manualHint() {
        return str(MANUAL_HINT);
    }

    /** 人工要求完全重写 (不参考上一稿) */
    public boolean manualFullRewrite() {
        return boolVal(MANUAL_FULL_REWRITE, false);
    }

    /** 世界观人工复审: 人工要求重新生成世界观/人物设定 */
    public boolean worldRerun() {
        return boolVal(WORLD_RERUN, false);
    }

    /** 世界观人工复审: 人工给设定师的修改意见 */
    public String worldHint() {
        return str(WORLD_HINT);
    }

    /** 分卷大纲人工复审: 人工要求重新生成本卷分章大纲 */
    public boolean volumeRerun() {
        return boolVal(VOLUME_RERUN, false);
    }

    /** 分卷大纲人工复审: 人工给分卷大纲师的修改意见 */
    public String volumeHint() {
        return str(VOLUME_HINT);
    }

    public String rollingSummary() {
        return str(ROLLING_SUMMARY);
    }

    public String characterStates() {
        return str(CHARACTER_STATES);
    }

    @SuppressWarnings("unchecked")
    public List<String> chapterSummaries() {
        return this.<List<String>>value(CHAPTER_SUMMARIES).orElse(List.of());
    }

    /** 全部章节是否已写完 (由摘要节点写入) */
    public boolean done() {
        return boolVal(DONE, false);
    }
}
