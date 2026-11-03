package com.novel.agent.novel.agent;

import com.novel.agent.novel.AgentRole;
import com.novel.agent.novel.FiveActStructure;
import com.novel.agent.novel.NovelContextBuilder;
import com.novel.agent.novel.NovelGenre;
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
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能体 2: 大纲师
 * <p>
 * 按"分卷滚动"策略产出, 避免把全书细纲一次性定死, 也避免章节数很大时单次输出被模型上限截断:
 * 1) 一次生成 "全局故事架构 (含分卷规划) + 写作控制台 JSON";
 * 2) 只生成第 1 卷 ({@link OutlineVolumes#VOLUME_SIZE} 章) 的逐章大纲;
 * 3) 后续各卷由 {@link VolumeOutlineAgent} 在写完上一卷后滚动生成, 每卷都用全局架构 + 已写摘要 + 伏笔台账约束。
 * <p>
 * 任一阶段模型返回空内容会重试一次, 仍为空则抛出异常让项目进入失败态并给出可操作提示,
 * 不再像以前那样把空大纲当成"生成成功"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutlineArchitectAgent implements NodeAction<NovelState> {

    /** 当前智能体角色 */
    private static final AgentRole ROLE = AgentRole.OUTLINE_ARCHITECT;

    /** 空输出重试次数 (不含首次) */
    private static final int GENERATE_RETRY = 1;

    /** 精确分隔符: prompt 明确要求模型输出这一串 */
    static final String CONSOLE_SEPARATOR = "===WRITE_CONSOLE_JSON===";
    /** 兜底: 模糊匹配模型可能用的变体 (中文标题 + 各种空白) */
    private static final Pattern SEPARATOR_FUZZY = Pattern.compile(
            "(?im)^\\s*={2,}\\s*(?:第?[三3]?[段部分]?\\s*[:：]?\\s*)?写作控制台\\s*(?:JSON|Json|json)?\\s*={2,}\\s*$");
    /** 兜底: 模糊匹配英文分隔符两侧的等号数量变化 */
    private static final Pattern JSON_SEPARATOR_FUZZY = Pattern.compile(
            "(?im)^\\s*={2,}\\s*WRITE_CONSOLE_JSON\\s*={2,}\\s*$");

    private final NovelModelFactory modelFactory;
    private final NovelProjectService projectService;
    private final NovelContextBuilder contextBuilder;

    @Override
    public Map<String, Object> apply(NovelState state) {
        long start = System.currentTimeMillis();
        Long projectId = state.projectId();
        int totalChapters = Math.max(1, state.totalChapters());
        NovelGenre genre = NovelGenre.of(state.genre());
        String[] ranges = computeFiveActRanges(totalChapters);

        // 阶段一: 全局故事架构 + 写作控制台 (一次调用)
        String globalRaw = generate(buildGlobalPrompt(state, totalChapters, genre, ranges), "全局架构与控制台");
        Separation result = splitOutlineAndConsole(globalRaw);
        String architecture = result.outline.trim();
        if (architecture.isBlank()) {
            throw new IllegalStateException("大纲师未返回全局故事架构, 请稍后重试");
        }
        String consoleJson = parseConsole(result.consoleRaw, projectId);

        // 阶段二: 只生成第 1 卷的分章大纲; 后续各卷在写完本卷后按卷滚动生成, 避免一次定死全书细纲
        int firstVolumeEnd = OutlineVolumes.lastChapter(1, totalChapters);
        projectService.updateStage(projectId, "正在生成第 1 卷分章大纲 (第 1-" + firstVolumeEnd
                + " 章 / 共 " + totalChapters + " 章)");
        String batchRaw = generate(buildChapterBatchPrompt(state, totalChapters, genre, ranges,
                        architecture, firstVolumeEnd),
                "第 1 卷分章大纲 1-" + firstVolumeEnd);
        String chapters = stripBatchDecorations(batchRaw);
        if (chapters.isBlank()) {
            throw new IllegalStateException("大纲师未返回第 1-" + firstVolumeEnd
                    + " 章的分章大纲, 请稍后重试");
        }

        String outline = (architecture + "\n\n" + chapters).trim();
        projectService.saveOutline(projectId, outline);
        projectService.saveWritingConsole(projectId, consoleJson);
        String stage = consoleJson.isBlank()
                ? "第 1 卷大纲已生成 (写作控制台缺失), 等待用户确认"
                : "第 1 卷大纲与写作控制台已生成, 等待用户确认";
        projectService.updateStage(projectId, stage);
        projectService.appendAgentLog(projectId, null, ROLE, stage,
                "目标章节数=" + totalChapters + ", 本次生成第 1 卷(" + 1 + "-" + firstVolumeEnd
                        + "章), 后续按卷滚动生成, 用户想法长度="
                        + (state.userIdea() == null ? 0 : state.userIdea().length())
                        + ", 控制台=" + (consoleJson.isBlank() ? "缺失" : "已生成"),
                outline, System.currentTimeMillis() - start);

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(NovelState.OUTLINE, outline);
        updates.put(NovelState.GLOBAL_ARCH, architecture);
        updates.put(NovelState.WRITING_CONSOLE, consoleJson);
        updates.put(NovelState.VOLUME_INDEX, 1);
        updates.put(NovelState.OUTLINE_HORIZON, firstVolumeEnd);
        updates.put(NovelState.STAGE, stage);
        return updates;
    }

    /** 调用大纲模型, 返回空内容时重试; 重试后仍为空则抛出可读异常 */
    private String generate(String prompt, String label) {
        String raw = "";
        for (int attempt = 0; attempt <= GENERATE_RETRY; attempt++) {
            String output = modelFactory.of(ROLE).generate(prompt);
            raw = output == null ? "" : output.trim();
            if (!raw.isBlank()) {
                return raw;
            }
            log.warn("[novel] 大纲师返回空内容, 准备重试 label={} attempt={}", label, attempt + 1);
        }
        throw new IllegalStateException("大纲师未返回内容 (" + label
                + "), 可能是模型思考 token 耗尽了输出上限; 请调大该角色的 max-tokens 后重试, 或减少章节数");
    }

    /** 解析写作控制台 JSON, 失败时降级为空串 (后续走无控制台的旧路径) */
    private String parseConsole(String consoleRaw, Long projectId) {
        String raw = consoleRaw == null ? "" : consoleRaw.trim();
        if (raw.isBlank()) {
            log.warn("[novel] 未找到写作控制台分隔符, 控制台置空 projectId={}", projectId);
            return "";
        }
        Optional<WritingConsole> parsed = StructuredOutputParser.parse(raw, WritingConsole.class);
        if (parsed.isEmpty()) {
            log.warn("[novel] 写作控制台 JSON 解析失败, 降级为空 projectId={}", projectId);
            return "";
        }
        return StructuredOutputParser.toJson(parsed.get());
    }

    /** 去掉模型可能附加的段标题/装饰分隔行/代码块围栏, 只保留分章大纲正文 */
    static String stripBatchDecorations(String raw) {
        String text = raw.replace("```markdown", "").replace("```md", "").replace("```", "");
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            // 纯装饰行 (=== 第二段 === / --- / ***) 直接丢弃, 不会误伤 "- 场景与目标" 这类要点行
            if (trimmed.matches("^[=*\\-]{3,}.*$")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    /** 四档分隔符匹配: 精确 → 各种 WRITE_CONSOLE_JSON 变体 → "写作控制台" 中文变体 → 末尾 JSON 兜底 */
    static Separation splitOutlineAndConsole(String raw) {
        // 1) 精确匹配换行包裹
        String wrapped = "\n" + raw + "\n";
        int idx = wrapped.indexOf("\n" + CONSOLE_SEPARATOR + "\n");
        if (idx >= 0) {
            return new Separation(
                    wrapped.substring(0, idx).trim(),
                    wrapped.substring(idx + CONSOLE_SEPARATOR.length() + 2).trim());
        }
        // 2) 正则匹配各种变体 (整行匹配, 只取第一个匹配)
        Matcher m = JSON_SEPARATOR_FUZZY.matcher(raw);
        if (!m.find()) {
            m = SEPARATOR_FUZZY.matcher(raw);
            if (!m.find()) {
                // 3) 兜底: 找不到分隔符 → 尝试剥离末尾独立的 JSON 对象
                int jsonStart = lastStandaloneJsonStart(raw);
                if (jsonStart > 0) {
                    return new Separation(raw.substring(0, jsonStart).trim(), raw.substring(jsonStart).trim());
                }
                return new Separation(raw, null);
            }
        }
        String outline = raw.substring(0, m.start()).trim();
        String console = raw.substring(m.end()).trim();
        return new Separation(outline, console);
    }

    /**
     * 兜底策略: 在 raw 字符串里找到最后一个独立的 JSON 对象起始位置。
     * 判定标准: 从后往前找第一个 '{' , 且它所在行前面只有空白或 *** / --- / === 这类装饰符。
     * 如果找到了, 把那行之前的所有内容当作 outline, 那行及之后当作 console JSON。
     * 注意: 返回值是相对原始 raw 的下标 (不能在清洗后的副本上计算, 否则会错位)。
     */
    static int lastStandaloneJsonStart(String raw) {
        int brace = raw.lastIndexOf('{');
        if (brace < 0) return -1;
        int lineStart = raw.lastIndexOf('\n', brace) + 1;
        String prefix = raw.substring(lineStart, brace).trim();
        // 行内除了装饰符不允许有其他文字, 否则说明这是正文里的花括号
        if (!prefix.isEmpty() && !prefix.matches("[=*\\-]+")) return -1;
        // 快速校验: 从这个 { 往后找配对的 }, 如果能找到就是真 JSON
        int depth = 0;
        for (int i = brace; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return lineStart; // 配对闭合, 确认是 JSON
                }
            }
        }
        return -1;
    }

    static class Separation {
        final String outline;
        final String consoleRaw;

        Separation(String outline, String consoleRaw) {
            this.outline = outline;
            this.consoleRaw = consoleRaw;
        }
    }

    /**
     * 根据 totalChapters 计算五段结构的精确章节区间, 强制模型使用。
     * 区间算法与摘要员的阶段推进共用 {@link FiveActStructure}, 保证口径一致。
     */
    private String[] computeFiveActRanges(int total) {
        return FiveActStructure.ranges(total);
    }

    /** 世界观/题材约束/用户想法: 两个阶段的 prompt 共用 */
    private void appendCommonContext(StringBuilder sb, NovelState state, NovelGenre genre) {
        sb.append("【世界观与人物设定】\n")
                .append(contextBuilder.worldviewBlock(state.worldview(), state.characters()))
                .append("\n\n");
        sb.append("【本题材写作约束 (分章大纲的场景设计与节奏安排须符合)】")
                .append(genre.getWritingConstraints()).append("\n\n");
        if (!state.userIdea().isBlank()) {
            sb.append("【用户的想法或已有大纲 (必须尊重并尽量保留其核心创意)】\n")
                    .append(state.userIdea())
                    .append("\n\n");
        }
    }

    /** 阶段一 prompt: 只输出全局故事架构 + 写作控制台 JSON, 不含分章大纲 */
    private String buildGlobalPrompt(NovelState state, int totalChapters, NovelGenre genre, String[] ranges) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是资深小说大纲师。本阶段只做一件事: 定死整部小说的全局故事架构, 并给出写作控制台 JSON。\n");
        sb.append("⚠ 硬约束: 整部小说必须且只能写 ").append(totalChapters)
                .append(" 章, 五段结构与分卷区间已为你算好, 不许私自增加或减少章节。\n");
        sb.append("本阶段不要输出分章大纲, 分章大纲只会先写第 1 卷, 后续各卷在写作过程中按卷滚动生成。\n\n");
        appendCommonContext(sb, state, genre);

        String volumePlan = buildVolumePlanLines(totalChapters);
        sb.append("""
                请先输出全局故事架构 (Markdown), 然后单独一行写精确分隔符, 分隔符之后是 JSON 对象。

                === 全局故事架构 (Markdown) ===
                严格按以下小节书写:

                ## 一句话故事
                (用一句话说清: 谁 + 想要什么 + 最大阻碍 + 代价)

                ## 全局主线
                - 主线目标: 主角贯穿全书要达成的具体目标
                - 核心冲突: 谁阻止他 / 冲突的本质
                - 赌注: 失败会失去什么
                - 结局锚点: 全书最终状态必须写明——冲突如何解决、主角最终得到或失去什么、关键配角与反派的下场、世界变成什么样

                ## 五段结构 (必须使用下面已经为你算好的章节区间, 一字不改)
                - 开端 (第 %s 章): 常态世界 + 激励事件
                - 发展 (第 %s 章): 升级对抗 + 关系变化
                - 转折·中点 (第 %s 章): 必须发生的那次认知翻转或重大失败
                - 高潮 (第 %s 章): 正面决战 + 代价兑现
                - 结局 (第 %s 章): 收束全部伏笔, 落到结局锚点

                ## 分卷规划 (每卷 %d 章, 共 %d 卷; 章节区间已经算好, 不得改动)
                每卷一行, 依次填写各卷目标/核心冲突/卷末高潮/留给下一卷的悬念:
                %s

                ## 角色弧光
                - 每人一行: 姓名 (起点状态 → 终点状态), 弧光的触发事件与章节

                ## 伏笔回收表
                - 每条一行: 伏笔 (埋设章节 → 回收章节 → 用什么方式兑现)

                全局架构要求:
                1. 五段结构的章节号必须连续覆盖第 1 章到第 %d 章, 不得出现空档或重叠;
                2. 每一卷的目标必须具体可判定, 且卷与卷之间要有升级关系, 不能各卷各自为战;
                3. 结局锚点必须具体可判定 (不能写"主角获得成长"这类空话);
                4. 各段之间的推进必须有因果关系, 只有最后一个段落收束完毕。

                ⚠️ 关键分隔行 (必须精确复制这一行, 一字不差, 前后不能加任何东西):
                ===WRITE_CONSOLE_JSON===

                分隔行之后, 紧接着写 JSON 对象。
                严禁把分隔行写成 *** / --- / === 第三段 === / === 写作控制台 === 等任何其他样式 ——
                你的程序只认 ===WRITE_CONSOLE_JSON===, 写别的样式会导致 JSON 被当作架构正文。
                """.formatted(ranges[0], ranges[1], ranges[2], ranges[3], ranges[4],
                OutlineVolumes.VOLUME_SIZE, OutlineVolumes.count(totalChapters), volumePlan, totalChapters));

        sb.append("""
                JSON 格式示例 (直接写, 不要用代码块围栏, 不要任何解释文字):
                {"phase":"当前阶段描述","phaseIndex":1,"mainLineGoal":"主线目标一句话","phaseGoal":"当前阶段应该推进到的主线状态","rhythmTag":"默认节奏标签(紧张/舒缓/转折/爆发/铺垫)","rhythmByChapter":{"1":"铺垫","5":"爆发","7":"舒缓"},"activeThreads":[{"id":"暗线-xxx","name":"暗线名称","hint":"写手不能提前透露的悬念","setupChapter":1,"resolveChapter":%d,"status":"setup","currentProgress":"还没展开"}],"pendingForeshadows":[{"id":"伏笔-xxx","description":"具体的伏笔描述","setupChapter":1,"resolveChapter":%d,"status":"planted"}],"avoidThisChapter":["默认禁止项"],"avoidByChapter":{"2":["本章不要让主角过早发现XX真相"],"7":["本章不能让反派直接退场"]}}

                JSON 字段约束:
                - phaseIndex 取 1-5, 对应五段结构 (开端/发展/中点/高潮/结局), 不要用三幕划分;
                - mainLineGoal 必须与全局架构的"全局主线"一致, 不许另起一条主线;
                - rhythmTag 是默认节奏, rhythmByChapter 只列需要偏离默认的章节 (转折/高潮/终章等关键章), key 为章节号字符串;
                - avoidThisChapter 是默认禁止项, avoidByChapter 只列个别章节特有的禁止项, key 为章节号字符串;
                - activeThreads 列出所有暗线 (至少 1 条), setupChapter 和 resolveChapter 必须落在 %d 章范围内;
                - pendingForeshadows 必须覆盖全局架构"伏笔回收表"里的每一条, setupChapter 必须小于 resolveChapter, 且都落在 %d 章范围内;
                - 所有章节号都必须是真实章号, 不要出现超出总章数的值。
                """.formatted(totalChapters, totalChapters, totalChapters, totalChapters));
        return sb.toString();
    }

    /** 机械给出每卷的章节区间, 模型只需填写每卷目标, 避免卷边界乱写 */
    private String buildVolumePlanLines(int totalChapters) {
        StringBuilder sb = new StringBuilder();
        for (int vol = 1; vol <= OutlineVolumes.count(totalChapters); vol++) {
            int from = OutlineVolumes.firstChapter(vol);
            int to = OutlineVolumes.lastChapter(vol, totalChapters);
            sb.append("- 第 ").append(vol).append(" 卷 (第 ").append(from).append("-").append(to)
                    .append(" 章): 本卷目标 / 核心冲突 / 卷末高潮 / 留给下一卷的悬念\n");
        }
        return sb.toString().trim();
    }

    /** 阶段二 prompt: 只输出第 1 卷 (第 1 章到第 to 章) 的逐章大纲 */
    private String buildChapterBatchPrompt(NovelState state, int totalChapters, NovelGenre genre, String[] ranges,
                                           String architecture, int to) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是资深小说大纲师。全局故事架构已经定稿, 本阶段只输出第 1 卷 (第 1 章到第 ")
                .append(to).append(" 章) 的逐章大纲; 全书共 ").append(totalChapters)
                .append(" 章, 其余各卷会在写作过程中按卷滚动生成, 本阶段不要提前写。\n\n");
        appendCommonContext(sb, state, genre);

        sb.append("【全局故事架构 (已定稿, 必须严格遵守, 不要改写)】\n")
                .append(NovelContextBuilder.truncateMiddle(architecture, 3000))
                .append("\n\n");
        sb.append("【五段结构章节区间】开端 ").append(ranges[0])
                .append(" / 发展 ").append(ranges[1])
                .append(" / 转折·中点 ").append(ranges[2])
                .append(" / 高潮 ").append(ranges[3])
                .append(" / 结局 ").append(ranges[4]).append("\n\n");
        sb.append("【本卷】第 1 卷, 第 1-").append(to).append(" 章\n\n");

        StringBuilder requirements = new StringBuilder();
        requirements.append("1. 只输出第 1 章到第 ").append(to).append(" 章, 共 ").append(to)
                .append(" 章; 章号连续, 不得跳号、不得重复、不得输出区间以外的章节;\n")
                .append("2. 每章严格按以下格式书写 (标题行 + 四条要点):\n\n")
                .append("## 第N章 标题\n")
                .append("- 场景与目标: ...\n")
                .append("- 核心冲突: ...\n")
                .append("- 悬念钩子: ...\n")
                .append("- 对应段落: 开端/发展/转折/高潮/结局 (取自全局架构五段结构)\n\n")
                .append("3. 各章之间要有因果关系与递进, 上一章的钩子必须在下一章得到推进;\n")
                .append("4. 本卷要有自己的小高潮与阶段性收束, 但不要提前解决全书主线;\n");
        if (to < totalChapters) {
            requirements.append("5. 最后一章的悬念钩子必须能引出第 ").append(to + 1).append(" 章 (下一卷开头);\n");
        } else {
            requirements.append("5. 本卷即全书结尾, 最后一章必须完成全部伏笔收束并落到结局锚点;\n");
        }
        requirements.append("6. 只输出分章大纲, 不要输出任何前言、结语、分隔符、JSON、全局架构或代码块围栏。\n");

        sb.append("【输出要求】\n").append(requirements);
        return sb.toString();
    }
}
