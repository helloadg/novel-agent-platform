package com.novel.agent.novel.config;

import com.novel.agent.novel.NovelState;
import com.novel.agent.novel.agent.ChapterPolisherAgent;
import com.novel.agent.novel.agent.ChapterWriterAgent;
import com.novel.agent.novel.agent.ConsistencyReviewerAgent;
import com.novel.agent.novel.agent.OutlineArchitectAgent;
import com.novel.agent.novel.agent.SummaryAgent;
import com.novel.agent.novel.agent.VolumeOutlineAgent;
import com.novel.agent.novel.agent.WorldBuilderAgent;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.PostgresSaverV2;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 小说写作协同图装配 (LangGraph4j StateGraph)
 *
 * <pre>
 * START → world_builder → await_world_review(世界观人工复审中断点)
 *            ├ 人工提意见重生成 → world_builder (循环)
 *            └ 人工放行         → outline_architect(全局架构 + 第1卷细纲) → await_outline(大纲确认中断点)
 *                                  → chapter_writer → reviewer
 *                                       ├ AI不通过(未超限)  → chapter_writer (自动重写, 循环至通过)
 *                                       └ AI通过(或超限)    → await_manual(人工复审中断点)
 *                                             ├ 人工提意见重写 → chapter_writer → reviewer(人工意见纳入审核标准) → await_manual
 *                                             └ 人工放行       → polisher → summarizer
 *                                                                   ├ next → next_chapter
 *                                                                   │         ├ 细纲还够用 → chapter_writer (逐章循环)
 *                                                                   │         └ 本卷写完   → volume_outline(生成下一卷)
 *                                                                   │                           → await_volume(分卷大纲人工复审中断点)
 *                                                                   │                               ├ 人工提意见重生成 → volume_outline (循环)
 *                                                                   │                               └ 人工放行         → chapter_writer
 *                                                                   └ done → END
 * </pre>
 *
 * 说明:
 * 1. 世界观/人物设定生成后进入 await_world_review, 人工可放行或提修改意见让设定师重生成;
 * 2. AI 审核不通过时写手自动重写 (受 chapter-max-rewrite 限制), 通过后才进入人工复审;
 * 3. 人工复审可放行, 也可提修改意见让写手重写; 人工重写后审核员必须把人工意见纳入审核标准再次审核,
 *    结果返回人工复审页面由人工最终判断 (即使 AI 认为通过, 人工仍可否决重写);
 * 4. 人工确认点用独立的 await_world_review/await_outline/await_manual 节点承载, 而不是 interruptBefore("chapter_writer");
 *    若把中断点直接放在 chapter_writer 上, 逐章循环(下一章又重新进入 chapter_writer)会再次触发中断,
 *    导致每章都需要人工恢复, 无法连续写作;
 * 5. 检查点持久化到 PostgreSQL, 同一作品共用一个 threadId, 支持中断恢复与失败续跑;
 * 6. state 值全部为可序列化类型 (String/Integer/Boolean/List), 适配 PostgresSaverV2 默认的 Java 序列化。
 */
@Slf4j
@Configuration
public class NovelGraphConfig {

    /** 图节点名: 设定师 */
    private static final String WORLD_BUILDER = "world_builder";
    /** 图节点名: 大纲师 */
    private static final String OUTLINE_ARCHITECT = "outline_architect";
    /** 图节点名: 大纲确认中断点 */
    private static final String AWAIT_OUTLINE = "await_outline";
    /** 图节点名: 世界观/人物设定人工复审中断点 */
    private static final String AWAIT_WORLD_REVIEW = "await_world_review";
    /** 图节点名: 写手 */
    private static final String CHAPTER_WRITER = "chapter_writer";
    /** 图节点名: 审核员 */
    private static final String REVIEWER = "reviewer";
    /** 图节点名: 人工干预中断点 */
    private static final String AWAIT_MANUAL = "await_manual";
    /** 图节点名: 润色编辑 */
    private static final String POLISHER = "polisher";
    /** 图节点名: 摘要员 */
    private static final String SUMMARIZER = "summarizer";
    /** 图节点名: 分卷大纲师 (写完一卷后生成下一卷细纲) */
    private static final String VOLUME_OUTLINE = "volume_outline";
    /** 图节点名: 分卷大纲人工复审中断点 */
    private static final String AWAIT_VOLUME = "await_volume";
    /** 图节点名: 进入下一章的流转节点 */
    private static final String NEXT_CHAPTER = "next_chapter";

    /** 超级步上限: 单章最多 6~9 步, 500 足够长篇小说, 同时防止逻辑缺陷导致死循环 */
    private static final int RECURSION_LIMIT = 500;

    @Bean
    public CompiledGraph<NovelState> novelGraph(DataSource pgDataSource,
                                                WorldBuilderAgent worldBuilderAgent,
                                                OutlineArchitectAgent outlineArchitectAgent,
                                                ChapterWriterAgent chapterWriterAgent,
                                                ConsistencyReviewerAgent consistencyReviewerAgent,
                                                ChapterPolisherAgent chapterPolisherAgent,
                                                SummaryAgent summaryAgent,
                                                VolumeOutlineAgent volumeOutlineAgent) throws Exception {
        BaseCheckpointSaver saver = PostgresSaverV2.builder()
                .datasource(pgDataSource)
                .stateSerializer(new ObjectStreamStateSerializer<>(NovelState::new))
                .createTables(true)
                .build();
        log.info("[novel] PostgresSaverV2 检查点存储初始化完成");

        StateGraph<NovelState> graph = new StateGraph<>(NovelState.SCHEMA, NovelState::new)
                .addNode(WORLD_BUILDER, AsyncNodeAction.node_async(worldBuilderAgent))
                .addNode(OUTLINE_ARCHITECT, AsyncNodeAction.node_async(outlineArchitectAgent))
                .addNode(AWAIT_WORLD_REVIEW, AsyncNodeAction.node_async(state ->
                        Map.of(NovelState.STAGE, "世界观与人物设定已生成, 等待人工复审")))
                .addNode(AWAIT_OUTLINE, AsyncNodeAction.node_async(state ->
                        Map.of(NovelState.STAGE, "大纲已生成, 等待用户确认")))
                .addNode(CHAPTER_WRITER, AsyncNodeAction.node_async(chapterWriterAgent))
                .addNode(REVIEWER, AsyncNodeAction.node_async(consistencyReviewerAgent))
                .addNode(AWAIT_MANUAL, AsyncNodeAction.node_async(state ->
                        Map.of(NovelState.STAGE,
                                "第 " + state.currentChapter() + " 章 AI 审核完成, 等待人工复审")))
                .addNode(POLISHER, AsyncNodeAction.node_async(chapterPolisherAgent))
                .addNode(SUMMARIZER, AsyncNodeAction.node_async(summaryAgent))
                .addNode(VOLUME_OUTLINE, AsyncNodeAction.node_async(volumeOutlineAgent))
                .addNode(AWAIT_VOLUME, AsyncNodeAction.node_async(state ->
                        Map.of(NovelState.STAGE,
                                "第 " + state.volumeIndex() + " 卷大纲已生成, 等待人工复审")))
                .addNode(NEXT_CHAPTER, AsyncNodeAction.node_async(NovelGraphConfig::advanceToNextChapter))
                .addEdge(GraphDefinition.START, WORLD_BUILDER)
                // 设定师生成后进入世界观人工复审中断点: 人工放行则继续大纲, 提意见则回设定师重生成
                .addEdge(WORLD_BUILDER, AWAIT_WORLD_REVIEW)
                .addConditionalEdges(AWAIT_WORLD_REVIEW,
                        AsyncEdgeAction.edge_async(state -> state.worldRerun() ? "rerun" : "approve"),
                        Map.of("rerun", WORLD_BUILDER,
                                "approve", OUTLINE_ARCHITECT))
                .addEdge(OUTLINE_ARCHITECT, AWAIT_OUTLINE)
                .addEdge(AWAIT_OUTLINE, CHAPTER_WRITER)
                .addEdge(CHAPTER_WRITER, REVIEWER)
                // 审核员路由:
                //   rewrite = AI 审核未通过且未超限, 写手自动重写
                //   accept  = AI 审核通过 / 超限 / 人工阶段复审完成, 进入人工复审
                // 注: 人工阶段 (manualRerun=true) 时审核员会把 rewriteNeeded 强制设为 false 并清掉 manualRerun,
                //     所以人工阶段也走 accept 分支到 await_manual, 由人工最终判断
                .addConditionalEdges(REVIEWER,
                        AsyncEdgeAction.edge_async(state -> state.rewriteNeeded() ? "rewrite" : "accept"),
                        Map.of("rewrite", CHAPTER_WRITER,
                                "accept", AWAIT_MANUAL))
                .addConditionalEdges(AWAIT_MANUAL,
                        AsyncEdgeAction.edge_async(state -> {
                            if (state.manualRerun()) return "rerun";
                            // 默认是人工放行 (approve), 继续正常流程
                            return "approve";
                        }),
                        Map.of("rerun", CHAPTER_WRITER,
                                "approve", POLISHER))
                .addEdge(POLISHER, SUMMARIZER)
                .addConditionalEdges(SUMMARIZER,
                        AsyncEdgeAction.edge_async(state -> state.done() ? "done" : "next"),
                        Map.of("next", NEXT_CHAPTER, "done", GraphDefinition.END))
                // 进入下一章前判断细纲是否够用: 写完整卷(细纲只覆盖到本卷末)时先生成下一卷, 再写作
                .addConditionalEdges(NEXT_CHAPTER,
                        AsyncEdgeAction.edge_async(state ->
                                state.currentChapter() > state.outlineHorizon() ? "volume" : "write"),
                        Map.of("volume", VOLUME_OUTLINE, "write", CHAPTER_WRITER))
                // 分卷大纲生成后进入人工复审中断点: 人工放行则继续写作, 提意见则重新生成本卷
                .addEdge(VOLUME_OUTLINE, AWAIT_VOLUME)
                .addConditionalEdges(AWAIT_VOLUME,
                        AsyncEdgeAction.edge_async(state -> state.volumeRerun() ? "rerun" : "approve"),
                        Map.of("rerun", VOLUME_OUTLINE,
                                "approve", CHAPTER_WRITER));

        return graph.compile(CompileConfig.builder()
                .checkpointSaver(saver)
                .interruptBefore(AWAIT_WORLD_REVIEW, AWAIT_OUTLINE, AWAIT_MANUAL, AWAIT_VOLUME)
                .recursionLimit(RECURSION_LIMIT)
                .build());
    }

    /** 进入下一章前清空上一章的临时字段, 避免审核意见/重写轮次/人工标记串到新章节 */
    private static Map<String, Object> advanceToNextChapter(NovelState state) {
        int next = state.currentChapter() + 1;
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(NovelState.CURRENT_CHAPTER, next);
        updates.put(NovelState.CHAPTER_TITLE, "");
        updates.put(NovelState.CHAPTER_OUTLINE, "");
        updates.put(NovelState.DRAFT, "");
        updates.put(NovelState.PREVIOUS_DRAFT, "");
        updates.put(NovelState.REVIEW, "");
        updates.put(NovelState.REWRITE_NEEDED, false);
        updates.put(NovelState.NEEDS_REVIEW, false);
        updates.put(NovelState.REWRITE_ROUNDS, 0);
        updates.put(NovelState.MANUAL_APPROVED, false);
        updates.put(NovelState.MANUAL_RERUN, false);
        updates.put(NovelState.MANUAL_HINT, "");
        updates.put(NovelState.MANUAL_FULL_REWRITE, false);
        updates.put(NovelState.VOLUME_RERUN, false);
        updates.put(NovelState.VOLUME_HINT, "");
        updates.put(NovelState.STAGE, "准备写作第 " + next + " 章");
        return updates;
    }
}
