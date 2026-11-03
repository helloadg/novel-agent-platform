package com.novel.agent.novel.agent;

import com.novel.agent.novel.AgentRole;
import com.novel.agent.novel.NovelContextBuilder;
import com.novel.agent.novel.NovelModelFactory;
import com.novel.agent.novel.NovelProjectService;
import com.novel.agent.novel.NovelState;
import com.novel.agent.novel.OutlineVolumes;
import com.novel.agent.novel.StructuredOutputParser;
import com.novel.agent.novel.model.WritingConsole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 智能体 2b: 分卷大纲师 (分卷滚动的"后续卷"部分)
 * <p>
 * 写完上一卷最后一章后触发: 以全局架构为骨架, 结合已写章节的摘要/人物状态/伏笔台账,
 * 只生成下一卷 ({@link OutlineVolumes#VOLUME_SIZE} 章) 的逐章大纲, 追加到既有大纲并落库。
 * <p>
 * 这样长篇不需要一次性定死全书细纲, 后面各卷能根据实际写作走向修正; 单卷失败也只影响该卷。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VolumeOutlineAgent implements NodeAction<NovelState> {

    /** 复用大纲师角色绑定 (模型档位/密钥与第一卷一致) */
    private static final AgentRole ROLE = AgentRole.OUTLINE_ARCHITECT;

    /** 空输出重试次数 (不含首次) */
    private static final int GENERATE_RETRY = 1;

    private final NovelModelFactory modelFactory;
    private final NovelProjectService projectService;
    private final NovelContextBuilder contextBuilder;

    @Override
    public Map<String, Object> apply(NovelState state) {
        long start = System.currentTimeMillis();
        Long projectId = state.projectId();
        int totalChapters = Math.max(1, state.totalChapters());
        int volumeNo = OutlineVolumes.volumeOf(state.currentChapter());
        int from = OutlineVolumes.firstChapter(volumeNo);
        int to = OutlineVolumes.lastChapter(volumeNo, totalChapters);

        if (from > totalChapters) {
            return Map.of();
        }

        // 人工驳回后重生成时, 先把上一版本卷内容从大纲里剥掉, 避免重复追加
        boolean regenerating = state.volumeRerun();
        // 细纲已覆盖到下一章时不重复生成 (老项目/一次性生成全书细纲的项目不会走到这里);
        // 但人工驳回重生成时 horizon 已经推进到本卷末, 必须绕过该守卫, 否则重生成会变成空操作
        if (!regenerating && state.currentChapter() <= state.outlineHorizon()) {
            return Map.of();
        }
        String previousVolumeBlock = "";
        if (regenerating) {
            previousVolumeBlock = extractVolumeBlock(state.outline(), volumeNo);
        }

        projectService.updateStage(projectId, "正在生成第 " + volumeNo + " 卷分章大纲 (第 "
                + from + "-" + to + " 章 / 共 " + totalChapters + " 章)");
        String raw = generate(buildPrompt(state, totalChapters, volumeNo, from, to, regenerating,
                        previousVolumeBlock, state.volumeHint()),
                "第 " + volumeNo + " 卷分章大纲 " + from + "-" + to);
        String chapters = OutlineArchitectAgent.stripBatchDecorations(raw);
        if (chapters.isBlank()) {
            throw new IllegalStateException("大纲师未返回第 " + from + "-" + to + " 章的分章大纲, 请稍后重试");
        }

        String baseOutline = regenerating
                ? stripVolumeBlock(state.outline(), volumeNo)
                : state.outline().trim();
        String outline = (baseOutline + "\n\n## 第" + volumeNo + "卷 分章大纲\n\n" + chapters).trim();
        projectService.saveOutline(projectId, outline);
        String stage = "第 " + volumeNo + " 卷大纲已生成, 等待人工复审";
        projectService.updateStage(projectId, stage);
        projectService.appendAgentLog(projectId, null, ROLE, stage,
                "滚动生成第 " + volumeNo + " 卷(" + from + "-" + to + "章), 已写至第 "
                        + (state.currentChapter() - 1) + " 章"
                        + (regenerating ? ", 人工驳回后重生成" : ""),
                chapters, System.currentTimeMillis() - start);

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(NovelState.OUTLINE, outline);
        updates.put(NovelState.OUTLINE_HORIZON, to);
        updates.put(NovelState.VOLUME_INDEX, volumeNo);
        updates.put(NovelState.VOLUME_RERUN, false);
        updates.put(NovelState.VOLUME_HINT, "");
        updates.put(NovelState.STAGE, stage);
        return updates;
    }

    /** 调用大纲模型, 返回空内容时重试; 重试后仍为空则抛出可读异常 */
    private String generate(String prompt, String label) {
        for (int attempt = 0; attempt <= GENERATE_RETRY; attempt++) {
            String output = modelFactory.of(ROLE).generate(prompt);
            String raw = output == null ? "" : output.trim();
            if (!raw.isBlank()) {
                return raw;
            }
            log.warn("[novel] 分卷大纲师返回空内容, 准备重试 label={} attempt={}", label, attempt + 1);
        }
        throw new IllegalStateException("大纲师未返回内容 (" + label
                + "), 可能是模型思考 token 耗尽了输出上限; 请调大该角色的 max-tokens 后重试");
    }

    private String buildPrompt(NovelState state, int totalChapters, int volumeNo, int from, int to,
                               boolean regenerating, String previousVolumeBlock, String volumeHint) {
        StringBuilder sb = new StringBuilder();
        if (regenerating && !volumeHint.isBlank()) {
            sb.append("⚠ 本轮是【人工驳回后的重生成】, 必须逐条落实人工修改意见, 未涉及的部分尽量保留上一版的结构。\n\n");
        }
        sb.append("你是资深小说大纲师。整部小说的全局架构已经定稿, 现在只为第 ").append(volumeNo)
                .append(" 卷生成逐章大纲 (第 ").append(from).append("-").append(to)
                .append(" 章, 全书共 ").append(totalChapters).append(" 章)。\n\n");
        if (regenerating && !volumeHint.isBlank()) {
            sb.append("【人工修改意见 (核心依据, 必须逐条落实)】\n")
                    .append(volumeHint)
                    .append("\n\n");
        }
        if (regenerating && !previousVolumeBlock.isBlank()) {
            sb.append("【上一版本卷大纲 (供参考, 未被指出问题的部分尽量保留)】\n")
                    .append(NovelContextBuilder.truncateMiddle(previousVolumeBlock, 2500))
                    .append("\n\n");
        }

        sb.append("【世界观与人物设定】\n")
                .append(contextBuilder.worldviewBlock(state.worldview(), state.characters()))
                .append("\n\n");
        sb.append("【全局故事架构 (已定稿, 必须严格遵守, 不要改写)】\n")
                .append(NovelContextBuilder.truncateMiddle(state.globalArch(), 3000))
                .append("\n\n");
        sb.append("【当前进度】已写完第 ").append(state.currentChapter() - 1).append(" 章, 本章群从第 ")
                .append(from).append(" 章开始\n\n");
        sb.append("【剧情总纲 (已经发生的事)】\n")
                .append(NovelContextBuilder.truncateMiddle(
                        state.rollingSummary().isBlank() ? "（暂无）" : state.rollingSummary(), 1500))
                .append("\n\n");
        sb.append("【人物当前状态】\n")
                .append(NovelContextBuilder.truncateMiddle(
                        state.characterStates().isBlank() ? "（暂无）" : state.characterStates(), 800))
                .append("\n\n");
        sb.append("【最近章节摘要】\n").append(recentSummaries(state.chapterSummaries())).append("\n\n");
        sb.append("【伏笔与暗线台账 (本卷要按计划推进/回收)】\n")
                .append(consoleThreadsBlock(state.writingConsole())).append("\n\n");

        sb.append("【输出要求】\n")
                .append("1. 只输出第 ").append(from).append(" 章到第 ").append(to).append(" 章, 共 ")
                .append(to - from + 1).append(" 章; 章号连续, 不得跳号、不得重复、不得输出区间以外的章节;\n")
                .append("2. 每章严格按以下格式书写 (标题行 + 四条要点):\n\n")
                .append("## 第N章 标题\n")
                .append("- 场景与目标: ...\n")
                .append("- 核心冲突: ...\n")
                .append("- 悬念钩子: ...\n")
                .append("- 对应段落: 本卷起承转合中的位置\n\n")
                .append("3. 第 ").append(from).append(" 章必须承接第 ").append(from - 1)
                .append(" 章结尾的悬念钩子, 不得重复已经写过的情节;\n")
                .append("4. 本卷要有自己的小高潮与阶段性收束, 但不得提前解决全书主线;\n")
                .append("5. 计划在本卷回收的伏笔必须真的安排回收章节, 不得遗漏;\n");
        if (to < totalChapters) {
            sb.append("6. 本卷最后一章要留下能引出第 ").append(to + 1).append(" 章 (下一卷开头) 的悬念;\n");
        } else {
            sb.append("6. 本卷即全书结尾, 最后一章必须完成全部伏笔收束并落到结局锚点;\n");
        }
        sb.append("7. 只输出分章大纲, 不要输出任何前言、结语、分隔符、JSON、全局架构或代码块围栏。\n");
        return sb.toString();
    }

    /** 最近 3 章摘要 (给本卷开头提供衔接上下文) */
    private String recentSummaries(List<String> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return "（暂无）";
        }
        int size = summaries.size();
        List<String> recent = summaries.subList(Math.max(0, size - 3), size);
        return NovelContextBuilder.truncateMiddle(String.join("\n", recent), 1200);
    }

    /** 从大纲里提取第 N 卷的分章大纲块 (含卷标题行) */
    private String extractVolumeBlock(String outline, int volumeNo) {
        String marker = "## 第" + volumeNo + "卷 分章大纲";
        int start = outline.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int next = outline.indexOf("\n## 第", start + marker.length());
        return next < 0 ? outline.substring(start).trim() : outline.substring(start, next).trim();
    }

    /** 从大纲里剥掉第 N 卷的分章大纲块 (重生成时避免重复追加) */
    private String stripVolumeBlock(String outline, int volumeNo) {
        String marker = "## 第" + volumeNo + "卷 分章大纲";
        int start = outline.indexOf(marker);
        if (start < 0) {
            return outline.trim();
        }
        int next = outline.indexOf("\n## 第", start + marker.length());
        if (next < 0) {
            return outline.substring(0, start).trim();
        }
        return (outline.substring(0, start) + outline.substring(next)).trim();
    }

    /** 控制台里的伏笔/暗线清单, 让本卷知道该推进或回收什么 */
    private String consoleThreadsBlock(String consoleJson) {
        Optional<WritingConsole> opt = StructuredOutputParser.parse(consoleJson, WritingConsole.class);
        if (opt.isEmpty()) {
            return "（控制台缺失, 按全局架构的伏笔回收表安排）";
        }
        WritingConsole console = opt.get();
        StringBuilder sb = new StringBuilder();
        if (console.getPendingForeshadows() != null) {
            for (WritingConsole.Foreshadow f : console.getPendingForeshadows()) {
                if ("resolved".equals(f.getStatus()) || "discarded".equals(f.getStatus())) {
                    continue;
                }
                sb.append("- 伏笔 ").append(f.getId()).append(": ").append(f.getDescription())
                        .append(" (埋于第").append(f.getSetupChapter()).append("章, 计划第")
                        .append(f.getResolveChapter()).append("章回收)\n");
            }
        }
        if (console.getActiveThreads() != null) {
            for (WritingConsole.Thread t : console.getActiveThreads()) {
                if ("resolved".equals(t.getStatus())) {
                    continue;
                }
                sb.append("- 暗线 ").append(t.getId()).append(": ").append(t.getName())
                        .append(" (计划第").append(t.getResolveChapter()).append("章兑现, 当前进度: ")
                        .append(t.getCurrentProgress() == null ? "未展开" : t.getCurrentProgress()).append(")\n");
            }
        }
        return sb.isEmpty() ? "（无待回收伏笔与暗线）" : sb.toString().trim();
    }
}
