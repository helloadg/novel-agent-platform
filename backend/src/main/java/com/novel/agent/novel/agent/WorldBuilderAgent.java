package com.novel.agent.novel.agent;

import com.novel.agent.novel.AgentRole;
import com.novel.agent.novel.NovelGenre;
import com.novel.agent.novel.NovelModelFactory;
import com.novel.agent.novel.NovelProjectService;
import com.novel.agent.novel.NovelState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 智能体 1: 设定师
 * 依据题材 + 用户想法构建世界观、规则体系与主要人物小传
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorldBuilderAgent implements NodeAction<NovelState> {

    /** 当前智能体角色 */
    private static final AgentRole ROLE = AgentRole.WORLD_BUILDER;
    /** 人物分节标题: 兼容 "## 主要人物" / "### 主要人物（3-6位）" / "**主要人物**" 等写法 */
    private static final String CHARACTER_HEADING = "## 主要人物";
    /** 人物分节的模糊匹配: 2-4 级标题或加粗标记 + "人物/角色" + 可选的"设定/小传"后缀 */
    private static final java.util.regex.Pattern CHARACTER_SECTION = java.util.regex.Pattern.compile(
            "(?m)^\\s*(?:#{2,4}\\s*|\\*\\*+\\s*)(?:主要)?(?:人物|角色)(?:设定|小传|介绍)?\\s*(?:\\*\\*+)?\\s*$");
    /**
     * 人物条目行兜底: 模型偶尔漏写 "## 主要人物" 标题, 直接输出 "**秦小刀** | 身份 | ..."。
     * 只认行首的加粗姓名/【姓名】后紧跟竖线, 避免把规则体系里的普通句子误判成人物区。
     */
    private static final java.util.regex.Pattern CHARACTER_ENTRY = java.util.regex.Pattern.compile(
            "(?m)^\\s*(?:\\*\\*+|【|「)[^|｜*】」\\n]{1,20}(?:\\*\\*+|】|」)\\s*[|｜]");

    private final NovelModelFactory modelFactory;
    private final NovelProjectService projectService;

    @Override
    public Map<String, Object> apply(NovelState state) {
        long start = System.currentTimeMillis();
        Long projectId = state.projectId();
        NovelGenre genre = NovelGenre.of(state.genre());

        // 复用已定稿的设定: 重新生成大纲时不要把人工复审过的世界观覆盖掉
        if (!state.worldRerun() && notBlank(state.worldview()) && notBlank(state.characters())) {
            String stage = "沿用已保存的世界观与人物设定, 等待人工复审";
            projectService.updateStage(projectId, stage);
            projectService.appendAgentLog(projectId, null, ROLE, stage, "复用已保存的设定, 未调用模型",
                    state.worldview() + "\n\n" + state.characters(), System.currentTimeMillis() - start);
            Map<String, Object> reuse = new LinkedHashMap<>();
            reuse.put(NovelState.WORLDVIEW, state.worldview());
            reuse.put(NovelState.CHARACTERS, state.characters());
            reuse.put(NovelState.WORLD_RERUN, false);
            reuse.put(NovelState.WORLD_HINT, "");
            reuse.put(NovelState.STAGE, stage);
            return reuse;
        }

        // 人工复审驳回时, worldHint 带有人工修改意见, 且 worldview/characters 中是上一稿
        boolean regenerating = state.worldRerun();
        String prompt = buildPrompt(genre, state.userIdea(), state.worldview(), state.characters(),
                state.worldHint());

        String output = modelFactory.of(ROLE).generate(prompt);
        String worldview = output.trim();
        String characters = "";
        // 依次尝试: 标准标题 → 标题变体 → 人物条目行兜底; 都失败才整段当世界观
        int idx = locateCharactersSection(output);
        if (idx > 0) {
            worldview = output.substring(0, idx).trim();
            characters = output.substring(idx).trim();
        } else {
            log.warn("[novel] 设定师输出未包含人物分节, 全量作为世界观, 人物留空 projectId={}", projectId);
        }

        projectService.saveWorldview(projectId, worldview, characters);
        String stage = regenerating
                ? "世界观与人物设定已按人工意见重生成, 等待人工复审"
                : "设定完成: 世界观与人物已就绪, 等待人工复审";
        projectService.updateStage(projectId, stage);
        projectService.appendAgentLog(projectId, null, ROLE, stage,
                "题材=" + genre.getLabel() + (regenerating ? " (人工意见重生成)" : ""),
                output, System.currentTimeMillis() - start);

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(NovelState.WORLDVIEW, worldview);
        updates.put(NovelState.CHARACTERS, characters);
        // 消费掉人工复审标记, 避免 await_world_review 条件边误判为再次重生成
        updates.put(NovelState.WORLD_RERUN, false);
        updates.put(NovelState.WORLD_HINT, "");
        updates.put(NovelState.STAGE, stage);
        return updates;
    }

    private String buildPrompt(NovelGenre genre, String userIdea,
                               String previousWorldview, String previousCharacters, String worldHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是资深小说设定师, 擅长为「").append(genre.getLabel()).append("」题材构建自洽且有张力的世界。\n");

        boolean hasHint = worldHint != null && !worldHint.isBlank();
        if (hasHint) {
            sb.append("⚠ 本轮是【人工驳回后的重生成】, 必须逐条落实下方的人工修改意见。\n\n");
        }

        sb.append("【题材基调】").append(genre.getDescription()).append('\n');
        sb.append("【本题材设定约束 (必须遵守)】").append(genre.getWorldviewConstraints()).append("\n\n");
        sb.append("【用户的想法或初始大纲】\n")
                .append(userIdea == null || userIdea.isBlank()
                        ? "（用户未提供, 请在题材范围内自由发挥）" : userIdea)
                .append("\n\n");

        if (hasHint) {
            sb.append("【人工修改意见 (本轮重生成的核心依据, 必须逐条落实)】\n")
                    .append(worldHint).append("\n\n");
        }

        // 重生成时带上一稿, 让设定师知道改哪里、保留哪里
        if (previousWorldview != null && !previousWorldview.isBlank()) {
            sb.append("【上一稿世界观 (请在此基础上按人工意见修改, 未被指出问题的部分尽量保留)】\n")
                    .append(previousWorldview).append("\n\n");
        }
        if (previousCharacters != null && !previousCharacters.isBlank()) {
            sb.append("【上一稿主要人物 (同上, 按人工意见修改)】\n")
                    .append(previousCharacters).append("\n\n");
        }

        sb.append("""
                请严格按下面的 Markdown 结构输出:
                ## 世界观
                （时代/地域/社会结构与权力格局/核心矛盾, 250-450 字）
                ## 规则体系
                %s
                ## 主要人物
                （必须保留这一行标题; 3-6 位核心人物, 每人一段: 姓名 | 身份 | 性格 | 说话风格与口头禅 | 目标 | 行为禁区 | 与主角的关系或冲突）

                要求:
                1. 世界观必须贴合题材基调, 不得混入其他题材的典型设定;
                2. 规则体系要自洽, 并为核心矛盾留出发展空间; 不适合本题材的条目 (如力量等级) 不要硬造, 可略过;
                3. 三个人物之间至少存在一条冲突线;
                4. 人物的说话风格必须具体到可直接写作 (用词习惯/句子长短/惯用语), 不要只写"性格冷漠"这类抽象描述;
                5. 人物说话风格要有真人感，不要刻意模板化;
                6. "## 主要人物" 必须单独成行原样输出, 不得省略该标题, 也不得把人物条目直接接在规则体系后面;
                7. 只输出上述结构内容, 不要开场白、不要解释、不要代码块。
                """.formatted(ruleSectionHint(genre)));
        return sb.toString();
    }

    /** 按题材给"规则体系"这一节的具体字段, 避免言情/都市/推理被硬塞力量境界体系 */
    private String ruleSectionHint(NovelGenre genre) {
        return switch (genre) {
            case YANQING -> "（情感规则: 关系阶段如何推进、误会与和解的合理机制、人物情感禁区, 200-350 字）";
            case LISHI -> "（制度与权谋规则: 势力格局、朝堂或军中的运作方式、时代限制与禁忌, 200-350 字）";
            case TUILI, XUANYI -> "（案件/悬念规则: 读者可获得的信息边界、诡计与反转的可行性依据、不能作弊的底线, 200-350 字）";
            case DUSHI -> "（现实规则与边界: 职业或异能设定、信息与资源的获取方式、不可违背的现实逻辑, 200-350 字）";
            default -> "（力量或科技来源、等级境界划分、使用的限制与代价, 200-350 字）";
        };
    }

    /**
     * 定位人物分节起点。
     * 1) 精确 "## 主要人物";
     * 2) 标题变体 ("### 人物设定" / "**主要角色**");
     * 3) 兜底: 模型漏写标题时, 用连续的人物条目行 (至少 2 条且相距不远) 判定人物区起点。
     */
    private int locateCharactersSection(String output) {
        int idx = output.indexOf(CHARACTER_HEADING);
        if (idx > 0) {
            return idx;
        }
        java.util.regex.Matcher section = CHARACTER_SECTION.matcher(output);
        if (section.find() && section.start() > 0) {
            return section.start();
        }
        java.util.regex.Matcher entry = CHARACTER_ENTRY.matcher(output);
        int firstStart = -1;
        int secondStart = -1;
        while (entry.find()) {
            if (firstStart < 0) {
                firstStart = entry.start();
            } else if (secondStart < 0 && entry.start() - firstStart <= 1500) {
                secondStart = entry.start();
            }
        }
        if (firstStart > 0 && secondStart > 0) {
            log.info("[novel] 未找到人物标题, 按连续人物条目行切分 offset={}", firstStart);
            return firstStart;
        }
        return -1;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
