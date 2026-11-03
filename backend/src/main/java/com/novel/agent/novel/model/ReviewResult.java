package com.novel.agent.novel.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 审核智能体的结构化输出
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewResult implements Serializable {

    /** 是否通过审核 */
    private boolean passed = true;

    /** 偏离项清单 */
    private List<Issue> issues = new ArrayList<>();

    /** 修改建议 */
    private List<String> suggestions = new ArrayList<>();

    /** 审核原文片段 (解析失败时留档排查, 不参与业务判断) */
    private String rawText;

    /**
     * 是否为降级结果 (模型调用异常或输出无法解析, 被判为"通过")。
     * 降级不阻断写作流程, 但要标记出来供人工抽查, 避免静默放行。
     */
    private boolean degraded;

    /**
     * 是否存在严重 (high) 偏离项 —— 只有这种情况才触发退回重写,
     * 避免轻微措辞差异导致无限重写
     */
    public boolean hasHighIssue() {
        return issues != null && issues.stream().anyMatch(Issue::isHigh);
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Issue implements Serializable {
        /** high / medium / low */
        private String severity;
        /** 偏离类型, 如 偏离大纲 / 设定矛盾 / 前文冲突 */
        private String type;
        /** 问题描述 (改了什么、为什么是问题) */
        private String description;
        /** 正文中对应的原句引用, 让写手知道改哪里 */
        private String quote;
        /** 具体修复建议 (什么方向改, 不要太模糊) */
        private String fix;

        public boolean isHigh() {
            return "high".equalsIgnoreCase(severity);
        }
    }

    /**
     * 供退回重写时注入 writer 的修改意见文本。
     * 重写时精简版 (只 high + 带原文引用), 让写手聚焦最关键的问题。
     */
    public String toRewriteInstruction() {
        StringBuilder sb = new StringBuilder();
        List<Issue> high = filterIssues("high");
        List<Issue> medium = filterIssues("medium");
        int issueCount = high.size() + medium.size();

        sb.append("审核发现 ").append(issueCount).append(" 条需要修正的问题");
        if (!high.isEmpty()) sb.append(" (").append(high.size()).append(" 条 HIGH 必须修复)");
        sb.append(":\n");

        int idx = 1;
        if (!high.isEmpty()) {
            sb.append("【HIGH 级问题 (必须修, 修完才算过审)】\n");
            for (Issue issue : high) {
                sb.append(idx++).append(". [HIGH] ").append(issue.getType() == null ? "" : issue.getType() + ": ")
                        .append(issue.getDescription()).append('\n');
                if (issue.getQuote() != null && !issue.getQuote().isBlank()) {
                    sb.append("   → 原文: 「").append(issue.getQuote()).append("」\n");
                }
                if (issue.getFix() != null && !issue.getFix().isBlank()) {
                    sb.append("   → 修复建议: ").append(issue.getFix()).append('\n');
                }
            }
        }
        if (!medium.isEmpty()) {
            sb.append("\n【MEDIUM 级问题 (尽量修, 修了更优)】\n");
            for (Issue issue : medium) {
                sb.append(idx++).append(". [MEDIUM] ").append(issue.getType() == null ? "" : issue.getType() + ": ")
                        .append(issue.getDescription()).append('\n');
                if (issue.getQuote() != null && !issue.getQuote().isBlank()) {
                    sb.append("   → 原文: 「").append(issue.getQuote()).append("」\n");
                }
                if (issue.getFix() != null && !issue.getFix().isBlank()) {
                    sb.append("   → 修复建议: ").append(issue.getFix()).append('\n');
                }
            }
        }

        if (suggestions != null && !suggestions.isEmpty()) {
            sb.append("\n【整体修改建议】\n");
            int sIdx = 1;
            for (String s : suggestions) {
                sb.append(sIdx++).append(". ").append(s).append('\n');
            }
        }
        return sb.toString();
    }

    /** 仅保留指定 severity 的 issues (不区分大小写) */
    private List<Issue> filterIssues(String severity) {
        if (issues == null) return new ArrayList<>();
        return issues.stream()
                .filter(i -> severity.equalsIgnoreCase(i.getSeverity()))
                .collect(java.util.stream.Collectors.toList());
    }
}
