package com.novel.agent.novel.agent;

import com.novel.agent.entity.NovelChapter;
import com.novel.agent.novel.AgentRole;
import com.novel.agent.novel.ChapterOutlineParser;
import com.novel.agent.novel.NovelContextBuilder;
import com.novel.agent.novel.NovelGenre;
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
import java.util.Map;
import java.util.Optional;

/**
 * 智能体 3: 写手
 * 依据设定 / 本章大纲 / 前情写正文; 被审核员退回时按修改意见重写
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChapterWriterAgent implements NodeAction<NovelState> {

    /** 当前智能体角色 */
    private static final AgentRole ROLE = AgentRole.CHAPTER_WRITER;
    /** 正文长度安全上限, 防止模型失控输出 */
    private static final int MAX_CONTENT_CHARS = 12000;
    /** 重写时注入上一稿正文的字数上限, 保证模型看得到原情节, 不会越改越短 */
    private static final int MAX_PREVIOUS_DRAFT_CHARS = 6000;
    /** 空输出重试次数 (不含首次) */
    private static final int GENERATE_RETRY = 1;

    /** 叙事视角与衔接约束: 首次写作与重写都必须遵守 */
    private static final String NARRATIVE_RULES = """
            【叙事与衔接】
            1. 全章固定一个视角人物, 只写该人物能看到、听到、知道的内容, 不随意切换视角;
            2. 【上一章结尾片段】只用于衔接场景与文风, 不要复述其已写过的内容, 直接从新情节开始;
            3. 叙述人称与全书保持一致, 不中途切换。
            """;

    /** 文风硬约束 + 交稿自查清单: 首次写作与重写都必须带上, 重写时尤其不能丢 */
    private static final String STYLE_AND_CHECKLIST = """
            【文风硬约束 (违反则审核退回重写)】

            一、用词与比喻
            1. 比喻 (像/如同/仿佛/宛如/好似) 全章不超过 3 处, 喻体必须与本体同属一个感官通道
               (听觉比听觉、视觉比视觉、触觉比触觉); 禁止"声音像一把镀金的锯"这类跨通道抽象比喻;
            2. 禁止连续两段使用比喻; 同一名词最多带 1 个形容词, 不堆叠修饰;
            3. 比喻与意象必须绑定视角人物的职业、生活经验或当下处境; 若同一个比喻换成任何角色、任何题材都成立,
               它就是套话, 必须改写或删除;
            4. 动笔前先在心里列出 3 个本题材"读者闭眼就能猜到"的标配意象, 本章全部不用;
            5. 场景与段落过渡用动作、时间推进、因果变化完成, 不用抒情排比或辞藻衔接;
               宁朴素准确, 不华丽含混; 喻体必须是视角人物当下能看见、听见或触摸到的具体事物。

            二、对话
            6. 角色说话必须像真人: 允许口头禅、省略句、被打断、语气词 (嗯/啊/吧/呢),
               不要每个角色都字正腔圆、逻辑严密、出口成章; 对话只写人物说了什么和做了什么,
               不额外用比喻去形容说话方式;
            7. 禁止解释性对话: 角色不得向另一个角色说明双方共知的规则、设定与常识
               (判据: 删掉这句话后对方真的会不知道吗? 不会就是废话, 删掉, 改用代价、失败、物件来体现);
            8. 对话必须带噪声: 每页至少 2 处无信息量内容 (跑题、重复、口误、打断、答非所问、聊日常琐事);
               每句都承载信息的对话就是 AI 腔;
            9. 金句限流: 每 500 字里"对仗/机锋/点题"式台词不超过 1 处, 全章最多 3 处; 其余对话允许平庸、说错、说一半;
            10. 禁止角色替作者点题: 不得出现总结人生、社会、命运的格言式台词, 主题只能通过选择与代价体现;
            11. 禁用句子: "我明白了""原来如此""这可真是""没想到""不得不说"这类空洞回应不要出现;
                "不禁""不由得""心中暗想""暗自思忖""眼中闪过""嘴角勾起""忽然""突然""沉默""停住""愣住"
                "没说话""笑了笑" 全章合计不超过 6 处;
            12. 心理描写要克制: 不要大段内心独白替读者总结情绪, 用动作和细节让读者自己感受;
                用"停顿 + 小动作 + 短句"表达情绪的写法全章最多 2 次, 情绪优先用角色本人的习惯动作与利益反应。

            三、角色声音 (动笔前先在心里定好, 写作时逐句对照人物设定里的"说话风格")
            13. 本章每个出场角色先定四项: 句长习惯 / 口头禅 / 用词层级 (粗俗·书面·行业词) / 情绪外露方式
                (外放·憋着·转移话题); 人物设定没写就按身份与处境补全, 不得所有角色同一套腔调;
            14. 去掉"某某说"之后, 读者仍能分辨是谁在说话; 多人对话不得腔调雷同。

            四、节奏
            15. 段落长短交替: 不要全篇每段都是 1-2 行的均匀切分; 每章至少 2 处 150 字以上的连续叙述段;
            16. 允许慢镜头: 每章留 1-2 段只写环境、关系、身体感受, 不推进剧情; 不是每个段落都必须产出信息点;
            17. 同一动作或微表情模板 (低头、看一眼继续做、手指停住等) 全章重复不得超过 2 次。

            五、设定自洽
            18. 描写设备、系统、档案、器物时, 用词必须与载体一致: 电子系统不得使用纸质或手写相关词
                (墨色、笔迹、纸张、边角翘起); 实体物件不得使用抽象界面词;
            19. 世界规则、机构行为、人物能力边界前后一致, 同一类行为在不同场合的处置标准不得矛盾。

            【写完后自查 (过一遍, 有任一项未达标就重写, 不要带着问题交稿)】
            1. 本章大纲的三个要素是否全部落在正文中: 场景与目标 / 核心冲突 / 悬念钩子
               (终章则检查结局锚点是否兑现, 伏笔是否全部回收, 不得留新悬念);
            2. 与前情提要是否存在前后矛盾 (人物状态、场景信息、已发生事件);
            3. 活跃暗线是否按控制台推进 (没到 resolveChapter 的不能提前揭穿);
            4. 是否违反【本章绝对不能发生】里列出的任何事件;
            5. 比喻数量与感官通道是否达标, 是否有可以套用到任何角色的套话意象;
            6. 对话: 随机抽 3 句读出来像不像真人会说的话; 有没有"角色互相解释双方已知设定"的句子;
               金句是否超过 3 处; 去掉"某某说"能否分辨是谁在说话;
            7. 停顿词与"停顿+小动作+短句"模板是否超限; 全章是否有至少 2 处 150 字以上的连续叙述段;
            8. 设备/系统/档案的用词是否与载体一致; 规则与人物能力边界是否前后自洽。
            """;

    private final NovelModelFactory modelFactory;
    private final NovelProjectService projectService;
    private final NovelContextBuilder contextBuilder;
    private final NovelProperties properties;

    @Override
    public Map<String, Object> apply(NovelState state) {
        long start = System.currentTimeMillis();
        Long projectId = state.projectId();
        int chapterNo = state.currentChapter();
        int totalChapters = state.totalChapters();

        // 本章大纲 (切分失败时降级为大纲全文 + 本章提示)
        Optional<ChapterOutlineParser.Entry> entry = ChapterOutlineParser.chapterAt(state.outline(), chapterNo);
        String chapterTitle = entry.map(ChapterOutlineParser.Entry::getTitle).orElse("第 " + chapterNo + " 章");
        String chapterOutline = entry.map(ChapterOutlineParser.Entry::getBody)
                .orElse("（大纲中未单列本章, 请依据整体结构推进）");

        // 重写上下文: 上一轮审核意见与已写轮次
        boolean rewriting = state.rewriteNeeded();
        int rewriteRounds = state.rewriteRounds() + (rewriting ? 1 : 0);
        ReviewResult lastReview = rewriting
                ? StructuredOutputParser.parse(state.reviewJson(), ReviewResult.class).orElse(null)
                : null;

        String prompt = buildPrompt(state, chapterNo, totalChapters, chapterOutline, rewriting, lastReview);
        String content = generate(prompt, projectId, chapterNo);
        if (content.length() > MAX_CONTENT_CHARS) {
            log.warn("[novel] 写手输出超长已截断 projectId={} chapter={} length={}", projectId, chapterNo, content.length());
            content = cutAtParagraph(content, MAX_CONTENT_CHARS);
        }

        projectService.saveDraft(projectId, chapterNo, chapterTitle, chapterOutline, content);
        projectService.updateCurrentChapter(projectId, chapterNo);
        String stage = rewriting
                ? "第 " + chapterNo + " 章重写中 (第 " + rewriteRounds + " 轮修改)"
                : "第 " + chapterNo + " 章正文已生成";
        projectService.updateStage(projectId, stage);
        projectService.appendAgentLog(projectId, chapterNo, ROLE, stage,
                "第 " + chapterNo + " 章 / " + chapterTitle + " / 目标 " + properties.getChapterTargetWords() + " 字",
                content, System.currentTimeMillis() - start);

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(NovelState.CHAPTER_TITLE, chapterTitle);
        updates.put(NovelState.CHAPTER_OUTLINE, chapterOutline);
        updates.put(NovelState.DRAFT, content);
        // 重写前备份上一稿: 审核员要拿它对比"是否删了情节/篇幅缩水"
        // 完全重写时审核员不做对比 (fullRewrite 标记由审核员消费), 但 PREVIOUS_DRAFT 仍备份供兜底
        updates.put(NovelState.PREVIOUS_DRAFT, rewriting ? resolvePreviousDraft(state, chapterNo) : "");
        // 注意: 不清空 REVIEW。审核节点要读上一轮审核意见, 做"是否真的修完"的增量核对
        updates.put(NovelState.REWRITE_NEEDED, false);
        // "完全重写"标记交由审核员消费: 审核员需要据此跳过"与上一稿对比"类检查
        // updates.put(NovelState.MANUAL_FULL_REWRITE, false);  // 移到审核员
        updates.put(NovelState.REWRITE_ROUNDS, rewriteRounds);
        updates.put(NovelState.STAGE, stage);
        return updates;
    }

    /**
     * 调用写手模型, 空输出重试一次; 仍为空则硬失败,
     * 避免把 0 字章节当成功落库后一路流进润色/摘要。
     */
    private String generate(String prompt, Long projectId, int chapterNo) {
        for (int attempt = 0; attempt <= GENERATE_RETRY; attempt++) {
            String output = modelFactory.of(ROLE).generate(prompt);
            String content = output == null ? "" : output.trim();
            if (!content.isBlank()) {
                return content;
            }
            log.warn("[novel] 写手返回空内容, 准备重试 projectId={} chapter={} attempt={}",
                    projectId, chapterNo, attempt + 1);
        }
        throw new IllegalStateException("写手未返回第 " + chapterNo
                + " 章正文, 可能是模型思考 token 耗尽了输出上限; 请调大该角色的 max-tokens 后重试");
    }

    private String buildPrompt(NovelState state, int chapterNo, int totalChapters, String chapterOutline,
                               boolean rewriting, ReviewResult lastReview) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是职业小说写手, 正在创作一部共 ").append(totalChapters)
                .append(" 章的小说, 现在写作第 ").append(chapterNo).append(" 章。\n\n");
        sb.append("【题材】").append(state.genre()).append('\n');
        NovelGenre genre = NovelGenre.of(state.genre());
        sb.append("【本题材写作约束 (必须遵守)】").append(genre.getWritingConstraints()).append("\n\n");

        if (rewriting) {
            // ==== 重写模式: 审核意见最优先, 但上一稿必须给全, 否则模型看不到原情节, 只会越改越短 ====
            // 人工勾选"完全重写"时相反: 不注入上一稿与问题原文, 让模型按大纲/设定/前情从零重写
            boolean fullRewrite = state.manualFullRewrite();
            // 人工发起的重写: 人工意见是本次重写的核心原因, 优先级最高; AI 审核意见次之
            boolean humanInitiated = state.manualRerun();
            java.util.List<String> quotes = fullRewrite ? java.util.List.of() : extractQuotes(lastReview);
            String previousDraft = fullRewrite ? "" : resolvePreviousDraft(state, chapterNo);
            int targetWords = properties.getChapterTargetWords();

            sb.append("═══════════════════════════════════════════════════════\n");
            if (fullRewrite) {
                sb.append("【⚠ 本轮任务是完全重写: 不提供上一稿, 按大纲/设定/前情从零写; ")
                        .append(humanInitiated ? "人工意见与审核意见必须遵守】\n\n" : "审核意见与人工提示必须遵守】\n\n");
            } else if (humanInitiated) {
                sb.append("【⚠ 本轮是人工发起的重写: 人工修改意见最高优先, 必须逐条落实; ")
                        .append("AI 审核意见次优先, 逐条修完才算过审】\n\n");
            } else {
                sb.append("【⚠ 本轮任务是重写: 审核意见最高优先, 逐条修完才算过审; 人工提示次优先】\n\n");
            }
            String hint = state.manualHint();
            if (hint != null && !hint.isBlank()) {
                sb.append("【人工修订提示 (用户明确要求)】\n").append(hint).append("\n\n");
            }

            if (lastReview != null) {
                sb.append("一、需要修的问题 (逐条对应):\n")
                        .append(lastReview.toRewriteInstruction()).append("\n");
            } else {
                sb.append("一、本轮审核意见缺失, 请按上一稿继续打磨: 保留全部情节与信息, 只改有问题的表达。\n\n");
            }

            if (!quotes.isEmpty()) {
                sb.append("二、必须重写或替换的原文片段 (只替换这些片段, 不要连带删掉整段情节):\n");
                for (int i = 0; i < quotes.size(); i++) {
                    sb.append("   问题").append(i + 1).append(": 「").append(quotes.get(i)).append("」\n");
                }
                sb.append('\n');
            }

            if (!previousDraft.isBlank()) {
                sb.append("三、上一稿正文 (新稿必须以此为基础改写, 完整保留审核未指出问题的情节/场景/对话):\n")
                        .append(NovelContextBuilder.truncateMiddle(previousDraft, MAX_PREVIOUS_DRAFT_CHARS))
                        .append("\n\n");
            }

            if (fullRewrite) {
                sb.append("四、完全重写要求:\n")
                        .append("   1. 本轮不参考上一稿, 请依据【本章大纲】【写作控制台】【前情提要】【人物设定】重新构思, ")
                        .append("允许调整场景顺序、节奏、叙述视角与段落划分;\n")
                        .append("   2. 上一稿被审核指出的问题 (尤其风格与前后一致性) 仍必须逐条避免, 不要重犯;\n")
                        .append("   3. 新稿篇幅约 ").append(targetWords).append(" 字, 大纲要求的场景/冲突/悬念钩子必须完整落地;\n")
                        .append("   4. 直接输出正文, 不要章节标题、不要任何解释。\n\n");
            } else {
                sb.append("四、重写铁律:\n")
                        .append("   1. 逐条修 HIGH 级问题, 修完后在新稿里该问题不应再出现;\n")
                        .append("   2. 允许重组段落与句子, 但不得删减审核没有标记为问题的情节、场景、人物对话与设定信息;\n")
                        .append("   3. 新稿篇幅应达到约 ").append(targetWords).append(" 字");
                if (!previousDraft.isBlank()) {
                    sb.append(", 且不得少于上一稿的 ").append(NovelProjectService.countWords(previousDraft)).append(" 字");
                }
                sb.append("; 需要压缩时只精简修辞与冗余句, 不能靠删情节压字数;\n")
                        .append("   4. 从根上解决问题, 不要只改表面措辞。\n\n");
            }
            sb.append("═══════════════════════════════════════════════════════\n\n");
        }

        sb.append("【世界观与人物设定】\n")
                .append(contextBuilder.worldviewBlock(state.worldview(), state.characters()))
                .append("\n\n");
        sb.append("【大纲】\n").append(contextBuilder.outlineBlock(state.outline(), chapterNo)).append("\n\n");

        // 全局锚点 + 进度: 让写手始终知道"全书要走到哪", 并在终章停止留钩子
        int remaining = totalChapters - chapterNo;
        boolean lastChapter = remaining <= 0;
        sb.append("【写作进度】第 ").append(chapterNo).append(" 章 / 共 ").append(totalChapters).append(" 章");
        if (remaining > 0) {
            sb.append(", 剩余 ").append(remaining).append(" 章");
        }
        sb.append('\n');
        if (lastChapter) {
            sb.append("【终章铁律】本章是全书最后一章: 必须完成主线收束, 兑现【伏笔清单】中所有尚未回收的伏笔, ")
                    .append("给出明确结局 (冲突如何解决 / 主角最终得到或失去什么 / 关键人物下场); ")
                    .append("禁止新增支线、新人物、新悬念, 禁止用开放式结尾回避收束。\n\n");
        }

        String console = contextBuilder.consoleBlock(state.writingConsole(), chapterNo, totalChapters);
        if (!console.isBlank()) {
            sb.append(console).append("\n\n");
        }

        String previous = contextBuilder.previousBlock(state.rollingSummary(), state.chapterSummaries(),
                lastChapterTail(state.projectId(), chapterNo));
        if (!previous.isBlank()) {
            sb.append("【前情提要】\n").append(previous).append("\n\n");
        }
        if (!state.characterStates().isBlank()) {
            sb.append("【人物当前状态】\n").append(state.characterStates()).append("\n\n");
        }

        if (!rewriting) {
            // ==== 首次写作模式: 完整的写作要求 + 文风约束 ====
            sb.append("写作要求:\n")
                    .append("1. 严格遵循【本章大纲】的场景、目标、核心冲突与悬念钩子, 不得新增与之矛盾的情节;\n");
            if (!console.isBlank()) {
                sb.append("2. 严格遵守【写作控制台】中的阶段目标, 按暗线条目提示推进 (未到兑现章的继续吊着, 到兑现章的收束), 到了该收的伏笔要兑现, 本章节奏要匹配给定标签;\n")
                        .append("3. 绝对不能写【本章绝对不能发生】里列出的任何事件;\n");
            }
            sb.append(!console.isBlank() ? "4" : "2").append(". 与前情提要、人物设定保持一致, 不得出现设定矛盾或人物凭空失忆、凭空获得未交代的能力;\n")
                    .append(!console.isBlank() ? "5" : "3").append(". 本章正文目标字数约 ").append(properties.getChapterTargetWords())
                    .append(" 字, 叙事完整, 结尾")
                    .append(lastChapter ? "闭合章节情节并推向全书结局;\n" : "留下悬念钩子;\n")
                    .append(!console.isBlank() ? "6" : "4").append(". 直接输出正文, 不要章节标题、不要任何解释、不要 Markdown 代码块。\n\n");
            sb.append(NARRATIVE_RULES);
            sb.append(STYLE_AND_CHECKLIST);
        } else {
            // ==== 重写模式: 精简版写作要求 + 聚焦审核发现的风格问题 ====
            sb.append("【重写注意事项】\n")
                    .append("1. 遵循【本章大纲】和【写作控制台】的阶段目标, 不要偏离;\n")
                    .append("2. 与前情提要、人物设定保持一致, 不要出现前后矛盾;\n")
                    .append("3. 直接输出正文, 不要章节标题、不要任何解释。\n\n");

            // 从 review issues 里提取风格类问题, 针对性提醒写手
            if (lastReview != null && lastReview.getIssues() != null) {
                java.util.List<String> styleHints = new java.util.ArrayList<>();
                for (ReviewResult.Issue issue : lastReview.getIssues()) {
                    if (issue.getType() != null && issue.getType().startsWith("风格-")) {
                        styleHints.add(issue.getType() + ": " + issue.getDescription());
                    }
                }
                if (!styleHints.isEmpty()) {
                    sb.append("【本次重写涉及的风格问题, 务必在新稿中彻底修复】\n");
                    for (String h : styleHints) {
                        sb.append("- ").append(h).append('\n');
                    }
                    sb.append('\n');
                }
            }
            // 重写同样要遵守文风硬约束并做交稿自查, 否则只会把旧问题再犯一遍
            sb.append(NARRATIVE_RULES);
            sb.append(STYLE_AND_CHECKLIST);
        }
        return sb.toString();
    }

    /** 超长截断: 尽量在段落边界切断, 避免把结尾钩子切在句子中间 */
    private static String cutAtParagraph(String content, int max) {
        String head = content.substring(0, max);
        int lastBreak = Math.max(head.lastIndexOf("\n\n"), head.lastIndexOf('\n'));
        return lastBreak > max * 2 / 3 ? head.substring(0, lastBreak).trim() : head.trim();
    }

    /** 上一稿正文: 优先用图状态里的 draft; 人工重写等场景状态里可能没有, 再从库中取本章正文兜底 */
    private String resolvePreviousDraft(NovelState state, int chapterNo) {
        String draft = state.draft();
        if (draft != null && !draft.isBlank()) {
            return draft;
        }
        if (state.projectId() == null) {
            return "";
        }
        for (NovelChapter chapter : projectService.listChapters(state.projectId())) {
            if (chapter.getChapterNo() != null && chapter.getChapterNo() == chapterNo
                    && chapter.getContent() != null) {
                return chapter.getContent();
            }
        }
        return "";
    }

    /** 从审核 issues 里提取所有 quote 字段 */
    private java.util.List<String> extractQuotes(ReviewResult review) {
        java.util.List<String> quotes = new java.util.ArrayList<>();
        if (review == null || review.getIssues() == null) return quotes;
        for (ReviewResult.Issue issue : review.getIssues()) {
            if (issue.getQuote() != null && !issue.getQuote().isBlank()) {
                quotes.add(issue.getQuote());
            }
        }
        return quotes;
    }

    /** 上一章结尾片段: 从库中取上一章正文, 保证刷新/续写后仍能衔接文风 */
    private String lastChapterTail(Long projectId, int chapterNo) {
        if (projectId == null || chapterNo <= 1) {
            return "";
        }
        String content = "";
        for (NovelChapter chapter : projectService.listChapters(projectId)) {
            if (chapter.getChapterNo() != null && chapter.getChapterNo() == chapterNo - 1
                    && chapter.getContent() != null) {
                content = chapter.getContent();
            }
        }
        return content;
    }
}
