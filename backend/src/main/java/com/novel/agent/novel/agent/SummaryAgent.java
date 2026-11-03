package com.novel.agent.novel.agent;

import com.novel.agent.novel.AgentRole;
import com.novel.agent.novel.FiveActStructure;
import com.novel.agent.novel.NovelModelFactory;
import com.novel.agent.novel.NovelProjectService;
import com.novel.agent.novel.NovelState;
import com.novel.agent.novel.StructuredOutputParser;
import com.novel.agent.novel.model.WritingConsole;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 智能体 6: 摘要员
 * 压缩本章摘要 + 人物状态快照 + 更新滚动总纲, 同时滚动更新写作控制台里的暗线/伏笔状态
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryAgent implements NodeAction<NovelState> {

    /** 当前智能体角色 */
    private static final AgentRole ROLE = AgentRole.SUMMARY;
    /** 解析失败时摘要的兜底截取长度 */
    private static final int FALLBACK_SUMMARY_CHARS = 200;

    private final NovelModelFactory modelFactory;
    private final NovelProjectService projectService;

    @Override
    public Map<String, Object> apply(NovelState state) {
        long start = System.currentTimeMillis();
        Long projectId = state.projectId();
        int chapterNo = state.currentChapter();
        int totalChapters = state.totalChapters();

        SummaryPayload payload = summarize(state);

        String summary = blank(payload.summary)
                ? NovelProjectService.truncate(state.draft(), FALLBACK_SUMMARY_CHARS)
                : payload.summary;
        String characterStates = blank(payload.characterStates)
                ? state.characterStates() : payload.characterStates;
        String rollingSummary = blank(payload.rollingSummary)
                ? mergeRolling(state.rollingSummary(), chapterNo, summary)
                : payload.rollingSummary;

        // 滚动更新写作控制台: 按章节号推进阶段与暗线, 伏笔是否回收只认模型回报的"本章实际兑现"
        String updatedConsoleJson = advanceConsole(state.writingConsole(), chapterNo, totalChapters,
                state.globalArch(), payload);

        projectService.saveSummary(projectId, chapterNo, summary);
        projectService.saveRollingSummary(projectId, rollingSummary);
        if (!updatedConsoleJson.equals(state.writingConsole())) {
            projectService.saveWritingConsole(projectId, updatedConsoleJson);
        }

        boolean done = chapterNo >= totalChapters;
        String stage = done
                ? "第 " + chapterNo + " 章完成, 全书写作结束"
                : "第 " + chapterNo + " 章完成, 准备写作第 " + (chapterNo + 1) + " 章";
        if (done) {
            projectService.markCompleted(projectId);
        } else {
            projectService.updateCurrentChapter(projectId, chapterNo);
        }
        projectService.updateStage(projectId, stage);
        projectService.appendAgentLog(projectId, chapterNo, ROLE, stage,
                "第 " + chapterNo + " 章摘要与人物状态",
                summary, System.currentTimeMillis() - start);

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(NovelState.CHAPTER_SUMMARIES, List.of("第 " + chapterNo + " 章: " + summary));
        updates.put(NovelState.ROLLING_SUMMARY, rollingSummary);
        updates.put(NovelState.CHARACTER_STATES, characterStates);
        updates.put(NovelState.WRITING_CONSOLE, updatedConsoleJson);
        updates.put(NovelState.DONE, done);
        updates.put(NovelState.STAGE, stage);
        return updates;
    }

    /**
     * 滚动更新写作控制台:
     * 1. 按章节号推进主线阶段 (phase/phaseIndex/phaseGoal) 与暗线状态;
     * 2. 伏笔是否回收只由摘要模型回报的"本章真实兑现"写入, 不再按计划回收章号机械置 resolved;
     * 控制台 JSON 解析失败时原样返回。
     */
    private String advanceConsole(String oldJson, int chapterNo, int totalChapters,
                                  String globalArch, SummaryPayload payload) {
        Optional<WritingConsole> opt = StructuredOutputParser.parse(oldJson, WritingConsole.class);
        if (opt.isEmpty()) {
            return oldJson;
        }
        WritingConsole console = opt.get();
        advancePhase(console, chapterNo, totalChapters, globalArch);
        advanceThreads(console, chapterNo);
        applyPayloadUpdates(console, payload);
        return StructuredOutputParser.toJson(console);
    }

    /**
     * 按章节号推进主线阶段: 用与大纲师一致的五段区间判定当前阶段,
     * phaseGoal 从全局架构的"五段结构"小节里取对应段的描述, 保证每章都跟着阶段走。
     */
    private void advancePhase(WritingConsole console, int chapterNo, int totalChapters, String globalArch) {
        int phaseIndex = FiveActStructure.phaseOf(chapterNo, totalChapters);
        console.setPhaseIndex(phaseIndex);
        console.setPhase("第 " + phaseIndex + " 段 · " + FiveActStructure.phaseName(phaseIndex));
        console.setPhaseGoal(phaseGoalFromArch(globalArch, phaseIndex));
    }

    /** 从全局架构的"五段结构"小节按顺序取第 N 段冒号后的描述, 取不到返回空串 */
    private String phaseGoalFromArch(String globalArch, int phaseIndex) {
        if (globalArch == null || globalArch.isBlank()) {
            return "";
        }
        boolean inSection = false;
        int seen = 0;
        for (String line : globalArch.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                inSection = trimmed.contains("五段结构");
                continue;
            }
            if (!inSection || trimmed.isEmpty()) {
                continue;
            }
            if (!trimmed.startsWith("-") && !trimmed.startsWith("*")) {
                continue;
            }
            seen++;
            if (seen != phaseIndex) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                colon = trimmed.indexOf('：');
            }
            return colon < 0 ? "" : trimmed.substring(colon + 1).trim();
        }
        return "";
    }

    /** 按章节号推进暗线状态 (模型没回报时的进度近似); 伏笔状态只认模型回报, 避免"到点即已收" */
    private void advanceThreads(WritingConsole console, int chapterNo) {
        List<WritingConsole.Thread> threads = console.getActiveThreads();
        if (threads == null) {
            return;
        }
        for (WritingConsole.Thread t : threads) {
            if (t.getSetupChapter() != null && chapterNo >= t.getSetupChapter()
                    && !"progress".equals(t.getStatus()) && !"resolved".equals(t.getStatus())) {
                t.setStatus("progress");
            }
            if (t.getResolveChapter() != null && chapterNo >= t.getResolveChapter()) {
                t.setStatus("resolved");
            }
        }
    }

    /**
     * 按模型回报的"本章实际埋/收"覆盖机械推进:
     * 正文漏收伏笔时不再假装已收, 暗线进展也用真实进展而不是章节号推断。
     */
    private void applyPayloadUpdates(WritingConsole console, SummaryPayload payload) {
        if (payload == null) {
            return;
        }
        List<WritingConsole.Foreshadow> foreshadows = console.getPendingForeshadows();
        List<String> resolvedIds = payload.getResolvedForeshadowIds();
        if (foreshadows != null && resolvedIds != null && !resolvedIds.isEmpty()) {
            for (WritingConsole.Foreshadow f : foreshadows) {
                if (f.getId() != null && resolvedIds.contains(f.getId()) && !"discarded".equals(f.getStatus())) {
                    f.setStatus("resolved");
                }
            }
        }

        List<WritingConsole.Thread> threads = console.getActiveThreads();
        List<SummaryPayload.ThreadUpdate> updates = payload.getThreadProgress();
        if (threads == null || updates == null || updates.isEmpty()) {
            return;
        }
        for (SummaryPayload.ThreadUpdate u : updates) {
            if (u.getId() == null) continue;
            for (WritingConsole.Thread t : threads) {
                if (!u.getId().equals(t.getId())) continue;
                if (u.getStatus() != null && !u.getStatus().isBlank()) {
                    t.setStatus(u.getStatus());
                }
                if (u.getCurrentProgress() != null && !u.getCurrentProgress().isBlank()) {
                    t.setCurrentProgress(u.getCurrentProgress());
                }
            }
        }
    }

    /** 摘要调用: 超时/报错/解析失败统一降级为"正文截断", 不阻断后续章节 */
    private SummaryPayload summarize(NovelState state) {
        SummaryPayload fallback = new SummaryPayload();
        fallback.setSummary("");
        fallback.setCharacterStates("");
        fallback.setRollingSummary("");
        try {
            String raw = modelFactory.of(ROLE).generate(buildPrompt(state));
            Optional<SummaryPayload> parsed = StructuredOutputParser.parse(raw, SummaryPayload.class);
            if (parsed.isPresent()) {
                return parsed.get();
            }
            log.warn("[novel] 摘要输出无法解析为 JSON, 降级为正文截断 projectId={} chapter={}",
                    state.projectId(), state.currentChapter());
        } catch (Exception e) {
            log.error("[novel] 摘要调用异常, 降级为正文截断 projectId={} chapter={}: {}",
                    state.projectId(), state.currentChapter(), e.getMessage());
        }
        return fallback;
    }

    /** 解析失败时的兜底: 旧总纲 + 本章摘要 */
    private String mergeRolling(String rollingSummary, int chapterNo, String summary) {
        String head = rollingSummary == null ? "" : rollingSummary.trim() + "\n";
        return head + "第 " + chapterNo + " 章: " + summary;
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private String buildPrompt(NovelState state) {
        return """
                你是小说编辑助理, 负责压缩章节信息供后续章节写作参考。

                【本章大纲】
                第 %d 章 %s
                %s

                【本章正文】
                %s

                【写作控制台里的伏笔与暗线 ID (回报状态时必须用这些 ID, 不要自造)】
                %s

                【上一版剧情总纲】
                %s

                请只输出如下 JSON, 不要任何解释文字, 不要代码块围栏:
                {"summary": "本章 300 字以内摘要, 含关键事件、关键转折与结尾悬念", "characterStates": "主要人物各自的状态快照, 每人一行: 姓名 | 位置/处境 | 已知信息 | 当前目标 | 情绪, 合计 400 字以内", "rollingSummary": "整合上一版总纲与本章进展后的剧情总纲, 400 字以内", "resolvedForeshadowIds": ["本章正文中真正兑现了的伏笔 ID, 没兑现就留空数组"], "threadProgress": [{"id": "暗线 ID", "status": "progress/resolved", "currentProgress": "这条暗线在本章推进到什么程度"}]}

                注意:
                1. resolvedForeshadowIds 只填正文里确实写出来兑现的伏笔, 不要因为到了计划回收章节就填上;
                2. threadProgress 只填本章真有推进的暗线, 没推进就不要填;
                3. 正文里没有兑现的伏笔不要谎报, 漏报错报都会让后续章节写歪。
                """.formatted(state.currentChapter(), state.chapterTitle(), state.chapterOutline(),
                state.draft(), consoleIdsBlock(state.writingConsole()),
                state.rollingSummary().isBlank() ? "（暂无）" : state.rollingSummary());
    }

    /** 给摘要员一份控制台 ID 清单, 避免它自造 ID 导致状态更新对不上 */
    private String consoleIdsBlock(String consoleJson) {
        Optional<WritingConsole> opt = StructuredOutputParser.parse(consoleJson, WritingConsole.class);
        if (opt.isEmpty()) {
            return "（控制台缺失, resolvedForeshadowIds 与 threadProgress 留空）";
        }
        WritingConsole console = opt.get();
        StringBuilder sb = new StringBuilder();
        if (console.getPendingForeshadows() != null) {
            for (WritingConsole.Foreshadow f : console.getPendingForeshadows()) {
                sb.append("- 伏笔 ").append(f.getId()).append(": ").append(f.getDescription())
                        .append(" (计划第").append(f.getResolveChapter()).append("章回收)\n");
            }
        }
        if (console.getActiveThreads() != null) {
            for (WritingConsole.Thread t : console.getActiveThreads()) {
                sb.append("- 暗线 ").append(t.getId()).append(": ").append(t.getName())
                        .append(" (计划第").append(t.getResolveChapter()).append("章兑现)\n");
            }
        }
        return sb.isEmpty() ? "（无伏笔与暗线）" : sb.toString().trim();
    }

    /** 摘要员的结构化输出 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SummaryPayload implements Serializable {
        private String summary = "";
        private String characterStates = "";
        private String rollingSummary = "";
        /** 本章真实兑现的伏笔 ID */
        private List<String> resolvedForeshadowIds = new ArrayList<>();
        /** 本章真实推进的暗线 */
        private List<ThreadUpdate> threadProgress = new ArrayList<>();

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class ThreadUpdate implements Serializable {
            private String id;
            /** progress / resolved */
            private String status;
            private String currentProgress;
        }
    }
}
