package com.novel.agent.novel.agent;

import com.novel.agent.novel.AgentRole;
import com.novel.agent.novel.NovelContextBuilder;
import com.novel.agent.novel.NovelModelFactory;
import com.novel.agent.novel.NovelProjectService;
import com.novel.agent.novel.NovelProperties;
import com.novel.agent.novel.NovelState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 智能体 5: 润色编辑
 * 只改善文字表达, 严格禁止改动情节/人物/事件; 未启用时节点直接透传
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChapterPolisherAgent implements NodeAction<NovelState> {

    /** 当前智能体角色 */
    private static final AgentRole ROLE = AgentRole.CHAPTER_POLISHER;
    /** 润色后长度低于原文该比例时判定为模型误删内容, 丢弃润色结果 (碎句改写通常只减少 5%-10%) */
    private static final double MIN_KEEP_RATIO = 0.85;

    private final NovelModelFactory modelFactory;
    private final NovelProjectService projectService;
    private final NovelContextBuilder contextBuilder;
    private final NovelProperties properties;

    @Override
    public Map<String, Object> apply(NovelState state) {
        long start = System.currentTimeMillis();
        Long projectId = state.projectId();
        int chapterNo = state.currentChapter();
        String draft = state.draft();

        if (!properties.isPolishEnabled() || draft.isBlank()) {
            return Map.of(NovelState.STAGE, "第 " + chapterNo + " 章润色已跳过");
        }

        String polished;
        try {
            polished = modelFactory.of(ROLE).generate(buildPrompt(state)).trim();
        } catch (Exception e) {
            // 润色是可选增强环节, 调用异常时保留原稿继续流程
            log.error("[novel] 润色调用异常, 保留原稿 projectId={} chapter={}: {}", projectId, chapterNo, e.getMessage());
            return Map.of(NovelState.STAGE, "第 " + chapterNo + " 章润色调用失败, 保留原稿");
        }
        if (polished.length() < draft.length() * MIN_KEEP_RATIO) {
            log.warn("[novel] 润色结果明显短于原文, 丢弃本次润色 projectId={} chapter={} before={} after={}",
                    projectId, chapterNo, draft.length(), polished.length());
            return Map.of(NovelState.STAGE, "第 " + chapterNo + " 章润色结果异常, 保留原稿");
        }

        projectService.savePolished(projectId, chapterNo, polished);
        String stage = "第 " + chapterNo + " 章润色完成";
        projectService.updateStage(projectId, stage);
        projectService.appendAgentLog(projectId, chapterNo, ROLE, stage,
                "第 " + chapterNo + " 章润色, 原 " + draft.length() + " 字符",
                "润色后 " + polished.length() + " 字符", System.currentTimeMillis() - start);

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(NovelState.DRAFT, polished);
        updates.put(NovelState.STAGE, stage);
        return updates;
    }

    private String buildPrompt(NovelState state) {
        return """
                你是资深文学编辑, 请对下面这一章正文做"减法润色": 目标是删掉多余的修饰, 让文字更准确干净。

                【硬性限制】不得改动以下任何内容:
                - 情节、事件、因果关系与对话内容
                - 人物姓名、身份、能力、人物关系
                - 章节的结局与悬念钩子
                - 段落顺序与整体结构

                【润色规则】
                1. 严禁新增原文没有的比喻、意象、形容词或抒情句, 只能删改不能添彩;
                2. 比喻 (像/如同/仿佛/宛如/好似) 超过 3 处时, 只保留最贴切、与视角人物当下感知一致的几处, 其余改写为直陈;
                3. 删掉同一名词上堆叠的多余形容词, 只保留最必要的一个;
                4. 把靠抒情或辞藻堆出来的段落过渡, 改成动作、时间或因果推进;
                5. 保留原文的叙事节奏与语感, 篇幅与原文的 85%% 以上持平, 不得明显变长;
                6. 润色后回读本章大纲: 场景与目标 / 核心冲突 / 悬念钩子必须仍然完整存在, 缺一即说明改坏了, 需还原对应段落;
                7. 去 AI 味 (只做减法, 不改情节):
                   - 删掉 AI 模板化台词 ("我明白了""原来如此""这可真是""没想到""不得不说"), 换成具体动作或更短的口语回应;
                   - "不禁""不由得""心中暗想""暗自思忖""眼中闪过""嘴角勾起"这类 AI 高频词能删就删, 改成直接写动作或表情;
                   - 对话过于书面化时, 在不改意思的前提下改成更口语的说法 (允许加"吧/呢/啊"等语气词, 允许短句化);
                   - 不要把所有角色的对话都润成一个腔调, 保留各人原有的说话方式差异。

                【本章大纲 (润色不得破坏其中的场景/冲突/钩子)】
                %s

                【世界观设定（仅供术语与语感参考）】
                %s

                【正文】
                %s

                请直接输出润色后的完整正文, 不要任何解释、不要标题、不要代码块。
                """.formatted(state.chapterOutline(),
                contextBuilder.worldviewBlock(state.worldview(), state.characters()), state.draft());
    }
}
