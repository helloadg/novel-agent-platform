package com.novel.agent.novel;

import com.novel.agent.entity.NovelProject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 协同图驱动器
 * <p>
 * 接口线程只负责触发, 真正的图执行在 novelExecutor 线程池中完成 (单部作品几十次串行模型调用,
 * 远超 HTTP 超时), 前端通过轮询进度接口获取状态。
 * <p>
 * 阶段一 (设定 + 大纲) 用到中断点 await_outline, 执行到那里挂起并落检查点;
 * 阶段二 (逐章写作) 通过 GraphInput.resume() 从检查点继续, 因此用户修改大纲后仍能衔接。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelGraphRunner {

    /** 检查点线程名前缀, 每个项目一条前端可恢复的执行流 */
    private static final String THREAD_PREFIX = "novel-";
    /** 从数据库恢复项目时, 若未记录总章数则使用的默认值 */
    private static final int DEFAULT_TOTAL_CHAPTERS = 3;

    private final CompiledGraph<NovelState> novelGraph;
    private final NovelProjectService projectService;

    /** 阶段一: 生成世界观/人物/大纲, 执行到 await_outline 前中断 */
    @Async("novelExecutor")
    public void startOutline(Long projectId) {
        NovelProject project = projectService.getProject(projectId);
        if (project == null) {
            log.warn("[novel] 项目不存在, 跳过大纲生成 projectId={}", projectId);
            return;
        }
        Map<String, Object> initial = new LinkedHashMap<>();
        initial.put(NovelState.PROJECT_ID, projectId);
        initial.put(NovelState.GENRE, project.getGenre());
        initial.put(NovelState.USER_IDEA, project.getUserIdea() == null ? "" : project.getUserIdea());
        initial.put(NovelState.TOTAL_CHAPTERS,
                project.getTotalChapters() == null ? DEFAULT_TOTAL_CHAPTERS : project.getTotalChapters());
        initial.put(NovelState.CURRENT_CHAPTER, 1);
        initial.put(NovelState.REWRITE_ROUNDS, 0);
        initial.put(NovelState.ROLLING_SUMMARY, "");
        initial.put(NovelState.STAGE, "开始生成世界观与人物设定");
        // 项目已有定稿设定时带上, 设定师会直接复用, 避免重新生成大纲时覆盖人工复审过的内容
        if (project.getWorldview() != null && !project.getWorldview().isBlank()) {
            initial.put(NovelState.WORLDVIEW, project.getWorldview());
        }
        if (project.getCharacters() != null && !project.getCharacters().isBlank()) {
            initial.put(NovelState.CHARACTERS, project.getCharacters());
        }

        RunnableConfig config = config(projectId);
        try {
            drain(novelGraph.stream(initial, config), projectId);
            if (hasPendingNode(config)) {
                String pending = pendingNode(config);
                if ("await_world_review".equals(pending)) {
                    // 世界观/人物设定生成完成, 等待人工复审 (保持 OUTLINING 状态)
                    projectService.updateStage(projectId, "世界观与人物设定已生成, 等待人工复审");
                    log.info("[novel] 设定阶段完成, 已挂起等待世界观复审 projectId={}", projectId);
                } else {
                    projectService.casStatus(projectId,
                            Set.of(NovelStatus.OUTLINING.name()),
                            NovelStatus.OUTLINE_READY, "大纲已生成, 等待用户确认");
                    log.info("[novel] 大纲阶段完成, 已挂起等待确认 projectId={}", projectId);
                }
            } else {
                log.warn("[novel] 大纲阶段未停在预期中断点 projectId={}", projectId);
            }
        } catch (Exception e) {
            log.error("[novel] 大纲阶段执行失败 projectId={}", projectId, e);
            projectService.markFailed(projectId, "大纲生成失败: " + e.getMessage());
        }
    }

    /** 是否存在可恢复的检查点 (大纲阶段失败后用于判断能否续跑) */
    public boolean hasResumableCheckpoint(Long projectId) {
        return hasPendingNode(config(projectId));
    }

    /**
     * 当前中断点节点名, null 表示执行未中断或无检查点。
     * 接口层用它校验"请求的人工干预是否真的落在对应中断点上", 避免改完 DB 状态后图静默不执行。
     */
    public String pendingNodeId(Long projectId) {
        return pendingNode(config(projectId));
    }

    /** 大纲阶段失败后的重试: 从检查点续跑失败节点 (通常是大纲师), 不重复已完成的设定生成 */
    @Async("novelExecutor")
    public void resumeOutline(Long projectId) {
        RunnableConfig config = config(projectId);
        log.info("[novel] 从检查点重试大纲生成 projectId={} pendingNode={}", projectId, pendingNode(config));
        try {
            drain(novelGraph.stream(GraphInput.resume(), config), projectId);
            if (hasPendingNode(config)) {
                String pending = pendingNode(config);
                if ("await_world_review".equals(pending)) {
                    projectService.updateStage(projectId, "世界观与人物设定已生成, 等待人工复审");
                    log.info("[novel] 设定阶段重试完成, 已挂起等待世界观复审 projectId={}", projectId);
                } else {
                    projectService.casStatus(projectId,
                            Set.of(NovelStatus.OUTLINING.name()),
                            NovelStatus.OUTLINE_READY, "大纲已生成, 等待用户确认");
                    log.info("[novel] 大纲重试完成, 已挂起等待确认 projectId={}", projectId);
                }
            } else {
                log.warn("[novel] 大纲重试未停在预期中断点 projectId={}", projectId);
            }
        } catch (Exception e) {
            log.error("[novel] 大纲重试失败 projectId={}", projectId, e);
            projectService.markFailed(projectId, "大纲生成失败: " + e.getMessage());
        }
    }

    /** 阶段二: 用户确认大纲后逐章写作, 从检查点恢复 */
    @Async("novelExecutor")
    public void resumeWriting(Long projectId) {
        RunnableConfig config = config(projectId);
        String nextNode = pendingNode(config);
        if (nextNode == null) {
            // 检查点缺失 (阶段一异常结束/项目被删除) 时无法凭空续写, 明确提示重新生成大纲
            log.warn("[novel] 无可用检查点, 无法继续写作 projectId={}", projectId);
            projectService.markFailed(projectId, "未找到可恢复的执行状态, 请重新生成大纲后再开始写作");
            return;
        }
        log.info("[novel] 从检查点恢复写作 projectId={} nextNode={} ", projectId, nextNode);
        try {
            drain(novelGraph.stream(GraphInput.resume(), config), projectId);
            if (hasPendingNode(config)) {
                // 跑到了中断点: AWAIT_MANUAL (硬门禁人工干预) / AWAIT_OUTLINE (大纲阶段等待确认) 等
                // 写作阶段的中断 (AWAIT_MANUAL) 保持 WRITING 状态不变, 方便后续人工干预接口继续;
                // 只有大纲阶段的中断 (AWAIT_OUTLINE) 才需要回到 OUTLINE_READY 等待用户确认
                String pending = pendingNode(config);
                if ("await_outline".equals(pending)) {
                    projectService.casStatus(projectId, Set.of(NovelStatus.WRITING.name()),
                            NovelStatus.OUTLINE_READY, "大纲已生成, 等待用户确认");
                } else {
                    log.info("[novel] 写作阶段跑到中断点, 保持 WRITING 状态 projectId={} pendingNode={}", projectId, pending);
                }
            } else {
                // 图完整跑到 END (全书写完), 标记为 COMPLETED
                projectService.casStatus(projectId, Set.of(NovelStatus.WRITING.name()),
                        NovelStatus.COMPLETED, "全部章节写作完成");
            }
        } catch (Exception e) {
            log.error("[novel] 写作阶段执行失败 projectId={}", projectId, e);
            projectService.markFailed(projectId, "写作阶段执行失败: " + e.getMessage());
        }
    }

    /** 用户修改大纲: 同步写入检查点状态, 使恢复执行时使用新大纲 */
    public void updateOutlineState(Long projectId, String outline) {
        try {
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(NovelState.OUTLINE, outline);
            // 人工改过的全局架构要同步更新, 否则后续滚动生成的新卷仍按旧架构写
            String architecture = ChapterOutlineParser.overview(outline);
            if (architecture != null && !architecture.isBlank()) {
                updates.put(NovelState.GLOBAL_ARCH, architecture);
            }
            novelGraph.updateState(config(projectId), updates);
        } catch (Exception e) {
            log.warn("[novel] 同步大纲到图状态失败 projectId={}", projectId, e);
        }
    }

    /**
     * 人工编辑章节正文: 同步写入检查点, 使后续人工放行时润色/摘要使用新稿,
     * 而不是图状态里被覆盖前的旧草稿。
     */
    public void updateDraftState(Long projectId, int chapterNo, String content) {
        try {
            novelGraph.updateState(config(projectId), Map.of(NovelState.DRAFT, content));
        } catch (Exception e) {
            log.warn("[novel] 同步人工编辑正文到图状态失败 projectId={} chapter={}", projectId, chapterNo, e);
        }
    }

    /** 用户人工编辑世界观与人物设定: 同步写入检查点状态, 放行/重生成时都以修改后的版本为准 */
    public void updateWorldState(Long projectId, String worldview, String characters) {
        try {
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(NovelState.WORLDVIEW, worldview);
            updates.put(NovelState.CHARACTERS, characters == null ? "" : characters);
            novelGraph.updateState(config(projectId), updates);
        } catch (Exception e) {
            log.warn("[novel] 同步世界观到图状态失败 projectId={}", projectId, e);
        }
    }

    /**
     * 世界观/人物设定人工复审: 放行, 继续生成大纲
     * 设 worldRerun=false, 恢复执行后 AWAIT_WORLD_REVIEW 条件边路由到 OUTLINE_ARCHITECT。
     */
    @Async("novelExecutor")
    public void worldApprove(Long projectId) {
        RunnableConfig config = config(projectId);
        String nextNode = pendingNode(config);
        if (!"await_world_review".equals(nextNode)) {
            log.warn("[novel] 当前不在世界观复审中断点, 忽略 world-approve 请求 projectId={} nextNode={}", projectId, nextNode);
            return;
        }
        log.info("[novel] 人工放行世界观设定, 继续生成大纲 projectId={}", projectId);
        try {
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(NovelState.WORLD_RERUN, false);
            updates.put(NovelState.WORLD_HINT, "");
            updates.put(NovelState.STAGE, "世界观设定已通过, 开始生成大纲");
            novelGraph.updateState(config, updates);
            drain(novelGraph.stream(GraphInput.resume(), config), projectId);
            // 放行后图会跑到 await_outline (大纲确认中断点), 状态升级为 OUTLINE_READY
            if (hasPendingNode(config) && "await_outline".equals(pendingNode(config))) {
                projectService.casStatus(projectId,
                        Set.of(NovelStatus.OUTLINING.name()),
                        NovelStatus.OUTLINE_READY, "大纲已生成, 等待用户确认");
            }
        } catch (Exception e) {
            log.error("[novel] 世界观放行后执行失败 projectId={}", projectId, e);
            projectService.markFailed(projectId, "世界观复审放行后执行失败: " + e.getMessage());
        }
    }

    /**
     * 世界观/人物设定人工复审: 驳回, 提修改意见让设定师重生成
     * 设 worldRerun=true, worldHint=人工意见, 恢复执行后 AWAIT_WORLD_REVIEW 路由回 WORLD_BUILDER。
     */
    @Async("novelExecutor")
    public void worldRerun(Long projectId, String hint) {
        RunnableConfig config = config(projectId);
        String nextNode = pendingNode(config);
        if (!"await_world_review".equals(nextNode)) {
            log.warn("[novel] 当前不在世界观复审中断点, 忽略 world-rerun 请求 projectId={} nextNode={}", projectId, nextNode);
            return;
        }
        boolean hasHint = hint != null && !hint.isBlank();
        log.info("[novel] 人工驳回世界观设定, 要求重生成 projectId={} hasHint={}", projectId, hasHint);
        try {
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(NovelState.WORLD_RERUN, true);
            updates.put(NovelState.WORLD_HINT, hasHint ? hint : "");
            updates.put(NovelState.STAGE, hasHint ? "人工驳回世界观, 按意见重生成中" : "人工驳回世界观, 重新生成中");
            novelGraph.updateState(config, updates);
            drain(novelGraph.stream(GraphInput.resume(), config), projectId);
        } catch (Exception e) {
            log.error("[novel] 世界观重生成执行失败 projectId={}", projectId, e);
            projectService.markFailed(projectId, "世界观重生成失败: " + e.getMessage());
        }
    }

    /**
     * 分卷大纲人工复审: 放行, 继续写作
     * 设 volumeRerun=false, 恢复执行后 AWAIT_VOLUME 条件边路由到 CHAPTER_WRITER。
     */
    @Async("novelExecutor")
    public void volumeApprove(Long projectId) {
        RunnableConfig config = config(projectId);
        String nextNode = pendingNode(config);
        if (!"await_volume".equals(nextNode)) {
            log.warn("[novel] 当前不在分卷大纲复审中断点, 忽略 volume-approve 请求 projectId={} nextNode={}", projectId, nextNode);
            return;
        }
        log.info("[novel] 人工放行第 {} 卷大纲, 继续写作 projectId={}", null, projectId);
        try {
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(NovelState.VOLUME_RERUN, false);
            updates.put(NovelState.VOLUME_HINT, "");
            updates.put(NovelState.STAGE, "分卷大纲已通过, 继续写作");
            novelGraph.updateState(config, updates);
            drain(novelGraph.stream(GraphInput.resume(), config), projectId);
        } catch (Exception e) {
            log.error("[novel] 分卷大纲放行后执行失败 projectId={}", projectId, e);
            projectService.markFailed(projectId, "分卷大纲复审放行后执行失败: " + e.getMessage());
        }
    }

    /**
     * 分卷大纲人工复审: 驳回, 提修改意见让分卷大纲师重生成
     * 设 volumeRerun=true, volumeHint=人工意见, 恢复执行后 AWAIT_VOLUME 路由回 VOLUME_OUTLINE。
     */
    @Async("novelExecutor")
    public void volumeRerun(Long projectId, String hint) {
        RunnableConfig config = config(projectId);
        String nextNode = pendingNode(config);
        if (!"await_volume".equals(nextNode)) {
            log.warn("[novel] 当前不在分卷大纲复审中断点, 忽略 volume-rerun 请求 projectId={} nextNode={}", projectId, nextNode);
            return;
        }
        boolean hasHint = hint != null && !hint.isBlank();
        log.info("[novel] 人工驳回分卷大纲, 要求重生成 projectId={} hasHint={}", projectId, hasHint);
        try {
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(NovelState.VOLUME_RERUN, true);
            updates.put(NovelState.VOLUME_HINT, hasHint ? hint : "");
            updates.put(NovelState.STAGE, hasHint ? "人工驳回分卷大纲, 按意见重生成中" : "人工驳回分卷大纲, 重新生成中");
            novelGraph.updateState(config, updates);
            drain(novelGraph.stream(GraphInput.resume(), config), projectId);
        } catch (Exception e) {
            log.error("[novel] 分卷大纲重生成执行失败 projectId={}", projectId, e);
            projectService.markFailed(projectId, "分卷大纲重生成失败: " + e.getMessage());
        }
    }

    /**
     * 人工放行当前卡住的章节 (硬门禁触发后)
     * 清除 needsReview/rewriteNeeded/rewriteRounds, 设 manualApproved=true,
     * 恢复图执行后 AWAIT_MANUAL 的条件边会路由到 POLISHER, 继续正常流程。
     */
    @Async("novelExecutor")
    public void approveAndContinue(Long projectId, String editedContent, String customHint) {
        RunnableConfig config = config(projectId);
        String nextNode = pendingNode(config);
        if (!"await_manual".equals(nextNode)) {
            log.warn("[novel] 当前不在人工干预中断点, 忽略 approve 请求 projectId={} nextNode={}", projectId, nextNode);
            return;
        }
        boolean hasHint = customHint != null && !customHint.isBlank();
        log.info("[novel] 人工放行第 {} 章, 继续写作 projectId={} hasHint={}", null, projectId, hasHint);
        try {
            // 1. 如果用户在前端编辑过正文, 先把新内容写回 DB 并同步到图状态
            if (editedContent != null && !editedContent.isBlank()) {
                NovelProject project = projectService.getProject(projectId);
                if (project != null) {
                    int chapterNo = project.getCurrentChapter() == null ? 1 : project.getCurrentChapter();
                    projectService.savePolished(projectId, chapterNo, editedContent);
                    novelGraph.updateState(config, Map.of(NovelState.DRAFT, editedContent));
                }
            }
            // 2. 清除所有硬门禁相关标记, 设人工放行标记, 注入人工提示词
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(NovelState.NEEDS_REVIEW, false);
            updates.put(NovelState.REWRITE_NEEDED, false);
            updates.put(NovelState.REWRITE_ROUNDS, 0);
            updates.put(NovelState.MANUAL_APPROVED, true);
            updates.put(NovelState.MANUAL_RERUN, false);
            updates.put(NovelState.REVIEW, "");
            updates.put(NovelState.STAGE, "人工放行, 继续写作");
            updates.put(NovelState.MANUAL_HINT, hasHint ? customHint : "");
            novelGraph.updateState(config, updates);
            // 3. 将 DB 中的 review_passed 置为 true (needsReview=true 那行需要覆盖)
            NovelProject project = projectService.getProject(projectId);
            if (project != null) {
                int chapterNo = project.getCurrentChapter() == null ? 1 : project.getCurrentChapter();
                projectService.markChapterReviewed(projectId, chapterNo, true);
            }
            // 4. 恢复图执行
            drain(novelGraph.stream(GraphInput.resume(), config), projectId);
        } catch (Exception e) {
            log.error("[novel] 人工放行后继续写作失败 projectId={}", projectId, e);
            projectService.markFailed(projectId, "人工干预后执行失败: " + e.getMessage());
        }
    }

    /**
     * 人工决定重写当前卡住的章节 (硬门禁触发后)
     * 重置 rewriteRounds/needsReview, 设 manualRerun=true,
     * 恢复图执行后 AWAIT_MANUAL 会路由回 CHAPTER_WRITER 再跑一轮。
     */
    @Async("novelExecutor")
    public void rerunChapter(Long projectId, String customHint, boolean fullRewrite) {
        RunnableConfig config = config(projectId);
        String nextNode = pendingNode(config);
        if (!"await_manual".equals(nextNode)) {
            log.warn("[novel] 当前不在人工干预中断点, 忽略 rerun 请求 projectId={} nextNode={}", projectId, nextNode);
            return;
        }
        boolean hasHint = customHint != null && !customHint.isBlank();
        log.info("[novel] 人工重写第 {} 章 projectId={} hasHint={} fullRewrite={}", null, projectId, hasHint, fullRewrite);
        try {
            Map<String, Object> updates = new LinkedHashMap<>();
            // 人工重写: 保留上一轮的 REVIEW 和 DRAFT, 让写手能拿到审核意见 + 上一版正文作为重写参考
            updates.put(NovelState.NEEDS_REVIEW, false);
            updates.put(NovelState.REWRITE_NEEDED, true);   // 写手会识别为"重写模式", 注入上一稿审核意见
            updates.put(NovelState.REWRITE_ROUNDS, 0);       // ⚠ 必须清零! 人工重写是额外尝试, 不继承之前的超限状态
            updates.put(NovelState.MANUAL_APPROVED, false);
            updates.put(NovelState.MANUAL_RERUN, true);
            // 注意: REVIEW / DRAFT 保持 checkpoint 里的原值 (就是上一轮审核发现的问题 + 上一版正文)
            updates.put(NovelState.STAGE, hasHint ? "人工触发本章重写 (含用户提示词)" : "人工触发本章重写");
            updates.put(NovelState.MANUAL_HINT, hasHint ? customHint : "");
            // 完全重写: 写手不再注入上一稿, 按大纲/设定/前情从零重写
            updates.put(NovelState.MANUAL_FULL_REWRITE, fullRewrite);
            novelGraph.updateState(config, updates);
            // 清 DB 中的 needs_review / review_passed 以便审核重新判定
            NovelProject project = projectService.getProject(projectId);
            if (project != null) {
                int chapterNo = project.getCurrentChapter() == null ? 1 : project.getCurrentChapter();
                projectService.markChapterReviewed(projectId, chapterNo, null);
            }
            drain(novelGraph.stream(GraphInput.resume(), config), projectId);
        } catch (Exception e) {
            log.error("[novel] 人工重写后执行失败 projectId={}", projectId, e);
            projectService.markFailed(projectId, "人工重写后执行失败: " + e.getMessage());
        }
    }

    /** 消费图输出流: 每个节点执行完成后记录一次进度 (业务落库由各智能体自己完成) */
    private void drain(AsyncGenerator.Cancellable<NodeOutput<NovelState>> stream, Long projectId) {
        for (NodeOutput<NovelState> output : stream) {
            NovelState state = output.state();
            if (state == null) {
                continue;
            }
            log.info("[novel] 节点执行完成 projectId={} node={} stage={}", projectId, output.node(), state.stage());
        }
    }

    private RunnableConfig config(Long projectId) {
        return RunnableConfig.builder().threadId(THREAD_PREFIX + projectId).build();
    }

    private boolean hasPendingNode(RunnableConfig config) {
        return pendingNode(config) != null;
    }

    /** 待执行节点名: 非空表示执行被中断 (等待人工确认或上次失败待续跑) */
    private String pendingNode(RunnableConfig config) {
        return novelGraph.lastStateOf(config)
                .map(StateSnapshot::nextNodeId)
                .filter(next -> next != null && !next.isBlank())
                .orElse(null);
    }
}
