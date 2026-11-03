package com.novel.agent.novel;

import lombok.Getter;

/**
 * 多智能体角色定义
 * 每个角色绑定一个模型档位 (profile): 写作类要高温度以激发创意, 审核类要低温度以保证结构化输出稳定
 */
@Getter
public enum AgentRole {

    /** 设定师: 生成世界观、规则体系与主要人物 */
    WORLD_BUILDER("world_builder", "world-builder", "设定师", "balanced"),
    /** 大纲师: 生成全局架构、逐章大纲与写作控制台 */
    OUTLINE_ARCHITECT("outline_architect", "outline-architect", "大纲师", "balanced"),
    /** 写手: 按大纲与前情撰写单章正文 */
    CHAPTER_WRITER("chapter_writer", "chapter-writer", "写手", "creative"),
    /** 审核员: 校验大纲要素、设定一致性与文风 */
    CONSISTENCY_REVIEWER("consistency_reviewer", "consistency-reviewer", "审核员", "strict"),
    /** 润色编辑: 只做减法 (压缩废话、削减比喻与冗余修饰), 不动情节, 故必须低温 */
    CHAPTER_POLISHER("chapter_polisher", "chapter-polisher", "润色编辑", "strict"),
    /** 摘要员: 压缩章节摘要并滚动更新写作控制台 */
    SUMMARY("summary", "summary", "摘要员", "strict");

    /** 图节点名, 同时作为协同日志的 agent_name */
    private final String nodeName;
    /** 角色-模型绑定表里的 key (页面与 DB 使用连字符风格) */
    private final String bindingKey;
    /** 页面展示用中文名 */
    private final String displayName;
    /** 对应 NovelProperties.profile 的档位 key */
    private final String profile;

    AgentRole(String nodeName, String bindingKey, String displayName, String profile) {
        this.nodeName = nodeName;
        this.bindingKey = bindingKey;
        this.displayName = displayName;
        this.profile = profile;
    }
}
