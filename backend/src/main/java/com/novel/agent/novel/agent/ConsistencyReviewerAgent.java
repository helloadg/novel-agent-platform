package com.novel.agent.novel.agent;

import com.novel.agent.novel.AgentRole;
import com.novel.agent.novel.ChapterOutlineParser;
import com.novel.agent.novel.NovelContextBuilder;
import com.novel.agent.novel.NovelModelFactory;
import com.novel.agent.novel.NovelProjectService;
import com.novel.agent.novel.NovelProperties;
import com.novel.agent.novel.NovelState;
import com.novel.agent.novel.StructuredOutputParser;
import com.novel.agent.novel.model.ReviewResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 智能体 4: 审核员
 * 核心职责: 检查正文是否偏离本章大纲、违背设定、与前文冲突, 决定是否需要重写。
 * <p>
 * 路由规则:
 * 1. 常规审核 (写手首稿或 AI 自动重写): 不通过则写手自动重写 (受 chapter-max-rewrite 限制),
 *    通过或超限则进入人工复审 await_manual;
 * 2. 人工提意见触发的重写 (manualRerun=true): 审核员必须把人工意见纳入审核标准再次审核,
 *    审核结果一律返回 await_manual 由人工最终判断 (即使 AI 认为通过, 人工仍可否决重写)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsistencyReviewerAgent implements NodeAction<NovelState> {

    /** 当前智能体角色 */
    private static final AgentRole ROLE = AgentRole.CONSISTENCY_REVIEWER;

    private final NovelModelFactory modelFactory;
    private final NovelProjectService projectService;
    private final NovelContextBuilder contextBuilder;
    private final NovelProperties properties;

    @Override
    public Map<String, Object> apply(NovelState state) {
        long start = System.currentTimeMillis();
        Long projectId = state.projectId();
        int chapterNo = state.currentChapter();
        int rewriteRounds = state.rewriteRounds();

        // 人工提意见触发的重写已完成, 审核员需要把人工意见纳入审核标准, 结果交人工最终判断
        boolean humanPhase = state.manualRerun();
        // 完全重写: 写手不参考上一稿从零重写, 审核员不做"与上一稿对比"类检查
        boolean fullRewrite = state.manualFullRewrite();

        String prompt = buildPrompt(state, chapterNo, rewriteRounds, humanPhase, fullRewrite);
        ReviewResult result = review(prompt);
        result = enforceLengthGuard(state, result, chapterNo, fullRewrite);

        // passed 与 high issue 自相矛盾时以 high issue 为准, 否则会漏判
        boolean effectivePassed = result.isPassed() && !result.hasHighIssue();
        boolean degraded = result.isDegraded();

        boolean exceedLimit = rewriteRounds >= properties.getChapterMaxRewrite();
        boolean hardProblem = !effectivePassed;
        // 人工阶段: 不再触发自动重写, 一律交人工判断
        // 常规阶段: 有硬问题且未超限 → 自动重写; 否则 → 进入人工复审
        boolean rewriteNeeded = !humanPhase && hardProblem && !exceedLimit;
        boolean needsReview = humanPhase || hardProblem;

        String reviewJson = StructuredOutputParser.toJson(result);
        projectService.saveReview(projectId, chapterNo, effectivePassed, reviewJson, needsReview, rewriteRounds);

        String stage;
        if (humanPhase) {
            stage = "第 " + chapterNo + " 章人工意见重写后 AI 复审完成 ("
                    + (effectivePassed ? "AI 通过" : "AI 未通过") + "), 等待人工复审";
        } else if (rewriteNeeded) {
            stage = "第 " + chapterNo + " 章 AI 审核未通过, 写手自动重写中 (第 " + (rewriteRounds + 1) + " 轮)";
        } else if (exceedLimit && hardProblem) {
            stage = "第 " + chapterNo + " 章重写超限 (" + rewriteRounds + " 轮), 等待人工复审";
        } else if (degraded) {
            stage = "第 " + chapterNo + " 章审核降级 (建议人工抽查), 等待人工复审";
        } else {
            stage = "第 " + chapterNo + " 章 AI 审核通过, 等待人工复审";
        }
        projectService.updateStage(projectId, stage);
        projectService.appendAgentLog(projectId, chapterNo, ROLE, stage,
                "第 " + chapterNo + " 章正文 " + NovelProjectService.countWords(state.draft()) + " 字, 重写轮次=" + rewriteRounds
                        + (humanPhase ? " (人工阶段)" : ""),
                (effectivePassed ? "passed=true\n" : "passed=false\n") + result.toRewriteInstruction(),
                System.currentTimeMillis() - start);

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(NovelState.REVIEW, reviewJson);
        updates.put(NovelState.REWRITE_NEEDED, rewriteNeeded);
        updates.put(NovelState.NEEDS_REVIEW, needsReview);
        updates.put(NovelState.STAGE, stage);
        // 人工阶段的审核完成后, 消费掉 manualRerun/manualHint, 避免 await_manual 条件边误判为"再次重写"
        if (humanPhase) {
            updates.put(NovelState.MANUAL_RERUN, false);
            updates.put(NovelState.MANUAL_HINT, "");
        }
        // 消费掉完全重写标记, 避免影响后续章节
        if (fullRewrite) {
            updates.put(NovelState.MANUAL_FULL_REWRITE, false);
        }
        return updates;
    }

    /**
     * 篇幅守卫: 模型有时对"删了大半情节"视而不见, 这里用确定性规则补一刀。
     * 重写稿明显短于上一稿, 或字数不到目标 70% 时, 强制记为 high 问题。
     * 完全重写 (fullRewrite) 时不做"与上一稿对比", 只检查是否达到目标字数。
     */
    private ReviewResult enforceLengthGuard(NovelState state, ReviewResult result, int chapterNo, boolean fullRewrite) {
        int target = properties.getChapterTargetWords();
        int current = NovelProjectService.countWords(state.draft());
        int previous = NovelProjectService.countWords(state.previousDraft());

        ReviewResult.Issue issue = null;
        if (!fullRewrite && previous > 0 && current < previous * 0.85) {
            issue = lengthIssue("篇幅缩水/情节删减",
                    "本稿 " + current + " 字, 上一稿 " + previous + " 字, 删减超过 15%, 可能丢掉了原稿的情节或场景",
                    "恢复被删掉的情节与场景, 只精简冗余修辞, 不要靠删情节压字数");
        } else if (current < target * 0.7) {
            issue = lengthIssue("篇幅不足",
                    "本稿 " + current + " 字, 目标 " + target + " 字, 明显偏短, 大纲要求的场景/冲突可能没有展开",
                    "把大纲要求的场景、核心冲突与悬念钩子完整展开到约 " + target + " 字");
        }
        if (issue == null) {
            return result;
        }
        log.warn("[novel] 篇幅守卫命中 projectId={} chapter={} issue={} current={} previous={}",
                state.projectId(), chapterNo, issue.getType(), current, previous);
        result.setPassed(false);
        if (result.getIssues() == null) {
            result.setIssues(new java.util.ArrayList<>());
        }
        result.getIssues().add(issue);
        return result;
    }

    private ReviewResult.Issue lengthIssue(String type, String description, String fix) {
        ReviewResult.Issue issue = new ReviewResult.Issue();
        issue.setSeverity("high");
        issue.setType(type);
        issue.setDescription(description);
        issue.setFix(fix);
        return issue;
    }

    /**
     * 审核调用: 模型超时/报错或解析失败都降级为"通过", 避免单次调用抖动中断整部作品的写作
     * (降级会在 issues 里留 low 级提示, 便于人工抽查)。
     */
    private ReviewResult review(String prompt) {
        try {
            String raw = modelFactory.of(ROLE).generate(prompt);
            Optional<ReviewResult> parsed = StructuredOutputParser.parse(raw, ReviewResult.class);
            if (parsed.isPresent()) {
                return parsed.get();
            }
            String retry = prompt + "\n\n重要: 只输出一个 JSON 对象, 不要任何解释文字, 不要代码块围栏。";
            String rawRetry = modelFactory.of(ROLE).generate(retry);
            parsed = StructuredOutputParser.parse(rawRetry, ReviewResult.class);
            if (parsed.isPresent()) {
                return parsed.get();
            }
            log.warn("[novel] 审核输出无法解析为 JSON, 降级为通过. raw={}", NovelProjectService.truncate(raw, 200));
            ReviewResult fallback = new ReviewResult();
            fallback.setPassed(true);
            fallback.setDegraded(true);
            fallback.setRawText(NovelProjectService.truncate(raw, 500));
            ReviewResult.Issue issue = new ReviewResult.Issue();
            issue.setSeverity("low");
            issue.setType("审核输出解析失败");
            issue.setDescription("审核模型未返回可解析的 JSON, 已降级为通过, 请人工抽查");
            fallback.setIssues(List.of(issue));
            return fallback;
        } catch (Exception e) {
            log.error("[novel] 审核调用异常, 降级为通过: {}", e.getMessage());
            ReviewResult fallback = new ReviewResult();
            fallback.setPassed(true);
            fallback.setDegraded(true);
            fallback.setRawText("审核调用失败: " + e.getMessage());
            ReviewResult.Issue issue = new ReviewResult.Issue();
            issue.setSeverity("low");
            issue.setType("审核调用失败");
            issue.setDescription("审核模型调用异常, 本章已放行, 请人工抽查");
            fallback.setIssues(List.of(issue));
            return fallback;
        }
    }

    private String buildPrompt(NovelState state, int chapterNo, int rewriteRounds, boolean humanPhase, boolean fullRewrite) {
        StringBuilder sb = new StringBuilder();
        boolean rewriting = rewriteRounds > 0 || state.rewriteNeeded();

        if (humanPhase) {
            sb.append("你是严苛的小说审核员, 本次审核是【人工提意见重写后的复审】。")
                    .append("人工已明确提出修改意见并让写手重写, 你必须把人工意见作为核心审核标准, ")
                    .append("逐条核查写手是否真的落实了这些修改意见。如果没有落实, 必须判为 high 级问题。\n\n");
        } else if (rewriting) {
            sb.append("你是严苛的小说审核员, 当前正在审核");
            if (rewriteRounds > 0) {
                sb.append("写手第 ").append(rewriteRounds).append(" 轮重写后的正文");
            } else {
                sb.append("写手被退回重写后的正文 (本次是第一次重写)");
            }
            sb.append("。请重点核查: 写手是否真的修完了上一轮的 HIGH 级问题, 以及有没有引入新问题。\n\n");
        } else {
            sb.append("你是严苛的小说审核员, 负责判断正文是否偏离大纲、设定、主线节奏与伏笔规划。\n\n");
        }

        if (fullRewrite) {
            sb.append("⚠ 本轮是【完全重写】: 写手未参考上一稿, 从零重新构思。")
                    .append("因此你不需要核查「是否删掉了上一稿的情节/场景」, 只按大纲/设定/前情审核新稿本身的质量即可。\n\n");
        }

        sb.append("【本章大纲（判定基准）】\n")
                .append("第 ").append(chapterNo).append(" 章 ")
                .append(state.chapterTitle()).append('\n')
                .append(state.chapterOutline())
                .append("\n\n");

        String console = contextBuilder.consoleBlock(state.writingConsole(), chapterNo, state.totalChapters());
        if (!console.isBlank()) {
            sb.append("【写作控制台（主线/暗线/伏笔/节奏判定基准）】\n").append(console).append("\n\n");
        }

        // 进度 + 全局结局锚点: 终章闭环判定的依据
        int remaining = state.totalChapters() - chapterNo;
        sb.append("【写作进度】第 ").append(chapterNo).append(" 章 / 共 ").append(state.totalChapters())
                .append(" 章").append(remaining > 0 ? ", 剩余 " + remaining + " 章" : ", 本章是终章").append("\n\n");
        String global = ChapterOutlineParser.overview(state.outline());
        if (!global.isBlank()) {
            sb.append("【全局故事架构 (结局闭环判定基准)】\n")
                    .append(NovelContextBuilder.truncateMiddle(global, 1000)).append("\n\n");
        }

        sb.append("【世界观与人物设定】\n")
                .append(contextBuilder.worldviewBlock(state.worldview(), state.characters()))
                .append("\n\n");
        String previous = contextBuilder.previousBlock(state.rollingSummary(), state.chapterSummaries(), "");
        if (!previous.isBlank()) {
            sb.append("【前情提要】\n").append(previous).append("\n\n");
        }

        // 增量感知: 重写时把上一轮的审核意见也给 reviewer, 让它对比验证
        if (rewriting && state.reviewJson() != null && !state.reviewJson().isBlank()) {
            Optional<ReviewResult> prevOpt = StructuredOutputParser.parse(state.reviewJson(), ReviewResult.class);
            if (prevOpt.isPresent()) {
                ReviewResult prev = prevOpt.get();
                sb.append("【上一轮审核意见 (请核查写手是否真的修了这些)】\n")
                        .append(prev.toRewriteInstruction()).append("\n\n");
            }
        }

        // 人工阶段: 人工提出的修改意见是本轮复审的核心标准, 必须逐条核查是否落实
        if (humanPhase) {
            String hint = state.manualHint();
            if (hint != null && !hint.isBlank()) {
                sb.append("【人工修改意见 (本轮复审核心标准, 逐条核查写手是否落实)】\n")
                        .append(hint).append("\n\n");
            } else {
                sb.append("【人工修改意见】人工未填写具体意见, 仅要求重写; 请按常规标准审核即可。\n\n");
            }
        }

        sb.append("【待审正文】\n").append(state.draft()).append("\n\n");

        // 重写审核必须带上一稿: 否则无从判断"是否删掉了原稿的情节", 这是重写越改越短的主因之一
        // 完全重写时不带上一稿 (写手本就没参考, 审核员也不该按上一稿对比)
        String previousDraft = state.previousDraft();
        if (rewriting && !fullRewrite && previousDraft != null && !previousDraft.isBlank()) {
            sb.append("【上一稿正文 (对比用, 核查新稿有没有删掉上一稿的情节/场景/对话)】\n")
                    .append(NovelContextBuilder.truncateMiddle(previousDraft, 6000)).append("\n\n");
            sb.append("【篇幅对比】上一稿 ").append(NovelProjectService.countWords(previousDraft))
                    .append(" 字, 本稿 ").append(NovelProjectService.countWords(state.draft()))
                    .append(" 字, 本章目标 ").append(properties.getChapterTargetWords()).append(" 字\n\n");
        }

        sb.append("""
                判定标准:
                - 若本轮是【人工提意见重写后的复审】(见上方【人工修改意见】): 写手未落实人工修改意见中的任何一条 → severity=high, type=人工意见未落实; 人工意见全部落实则不得因此判不通过
                - 正文未完成本章大纲的核心目标/核心冲突/结局走向 → severity=high, type=偏离大纲
                - 正文与世界观规则或人物设定矛盾 → severity=high, type=设定矛盾
                - 正文与前文情节或人物状态冲突 → severity=high, type=前文冲突
                - 逻辑漏洞、时间线错乱 → severity=high, type=逻辑漏洞
                - 提前揭穿了【活跃暗线】里还没到 resolveChapter 的暗线 → severity=high, type=暗线提前揭穿
                - 到了【伏笔清单】里 resolveChapter 却完全没兑现 → severity=medium, type=伏笔未兑现
                - 节奏标签要求紧张/爆发, 正文却写得拖沓平淡; 或要求舒缓/铺垫, 正文却一路快节奏冲剧情 → severity=medium, type=节奏不匹配
                - 违反【本章绝对不能发生】里的任何一条 → severity=high, type=提前写穿后续剧情
                - 本章是终章 (见【写作进度】) 却仍未收束主线、仍未回收伏笔清单里的条目、或结尾留下新悬念新支线 → severity=high, type=结局未闭环
                - 正文篇幅不到本章目标字数的 70%, 或重写稿明显短于上一稿 → severity=high, type=篇幅缩水/情节删减
                - 重写稿删掉了上一稿存在、且不应删除的情节/场景/人物对话 → severity=high, type=情节删减
                - 比喻 (像/如同/仿佛/宛如/好似) 超过 3 处, 或连续两段出现比喻 → severity=high, type=风格-比喻超量
                - 同一名词堆叠 3 个以上形容词, 或出现空转的抒情排比 → severity=high, type=风格-辞藻堆砌
                - 比喻跨感官通道或与本场景无关 (如"声音像一把镀金的锯") → severity=high, type=风格-比喻失当
                - 段落过渡靠抒情/辞藻而非动作与因果推进 → severity=medium, type=风格-过渡生硬
                - 出现 AI 模板化台词 ("我明白了""原来如此""这可真是""没想到""不得不说"等空洞回应) → severity=medium, type=风格-AI模板台词
                - 角色向对方解释双方共知的规则/设定/常识 (删掉该句对方也不会不知道) → severity=high, type=风格-解释性对话
                - "对仗/机锋/点题"式台词密度超标 (每 500 字超过 1 处, 或全章超过 3 处) → severity=medium, type=风格-金句过密
                - 出现总结人生/社会/命运的格言式台词 (角色替作者点题) → severity=medium, type=风格-角色点题
                - 对话全是有效信息, 没有任何跑题/重复/口误/打断/答非所问 (像剧本而非真人对话) → severity=medium, type=风格-对话无噪声
                - 比喻或意象可以套用到任何角色、任何题材 (与本视角人物的职业/经历/处境无关, 属于套话) → severity=medium, type=风格-套话意象
                - "忽然/突然/沉默/停住/愣住/没说话/笑了笑"全章合计超过 6 处, 或"停顿+小动作+短句"表情绪模板超过 2 次 → severity=medium, type=风格-停顿词滥用
                - 全篇每段都是 1-2 行的均匀切分, 没有任何 150 字以上的连续叙述段 → severity=medium, type=风格-段落节奏均匀
                - 不同角色说话方式无区分 (多人对话腔调雷同, 看不出谁在说话) → severity=medium, type=风格-角色无辨声
                - AI 高频词扎堆 ("不禁""不由得""心中暗想""暗自思忖""眼中闪过""嘴角勾起"全章超过 6 处) → severity=medium, type=风格-AI高频词
                - 对话过于书面化/完整 (每句都像写作文, 没有口语的省略、打断、语气词) → severity=low 或 medium, type=风格-对话不自然
                - 设备/系统/档案/器物的用词与载体不符 (电子系统出现墨色、笔迹、纸张、边角翘起等纸质描写, 或实体物件用抽象界面词) → severity=high, type=设定-载体术语错配
                - 同一类行为在不同场合处置标准矛盾, 机构或规则执行前后不一致 → severity=high, type=逻辑-规则执行不一致
                - 人物转变的前因与后果搭不上 (因果倒置), 或行为与既定利益动机不自洽 → severity=high, type=逻辑-动机不自洽
                - 全章视角人物不固定、叙述人称中途切换 → severity=high, type=视角混乱
                - 复述了上一章结尾已经写过的内容 → severity=medium, type=重复前文
                - 节奏拖沓、描写粗糙、对话生硬 → severity=medium 或 low
                - 正文忠实完成本章大纲且无上述问题时: passed=true, issues 为空数组
                (只做上述硬指标判定, 不做主观审美评价; 风格问题请在 description 里引用原句)

                关键要求: 每条 issue 必须填写 quote (正文里对应的精确原句, 80 字以内, 能定位) 和 fix (具体修复方向, 不要模糊).
                篇幅/删情节类问题没有对应原句时, quote 可填写被删情节的一句话概述.

                请只输出如下 JSON, 不要任何解释文字, 不要代码块围栏:
                {"passed": true, "issues": [{"severity": "high", "type": "偏离大纲", "description": "问题所在", "quote": "正文中对应的那句话", "fix": "具体改法"}, {"severity": "medium", "type": "节奏不匹配", "description": "...", "quote": "...", "fix": "..."}], "suggestions": ["整体修改建议"]}
                """);
        return sb.toString();
    }
}
