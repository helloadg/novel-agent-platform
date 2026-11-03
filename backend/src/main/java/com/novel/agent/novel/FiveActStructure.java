package com.novel.agent.novel;

/**
 * 五段结构 (开端/发展/转折·中点/高潮/结局) 的章节区间与阶段判定。
 * <p>
 * 大纲师用它把精确区间写进 prompt, 摘要员用同一口径推进写作控制台的 phase/phaseGoal,
 * 避免两处各自计算导致提示词要求与进度展示自相矛盾。
 */
public final class FiveActStructure {

    /** 阶段名, 下标 0-4 对应 phaseIndex 1-5 */
    public static final String[] PHASE_NAMES = {"开端", "发展", "转折·中点", "高潮", "结局"};

    private FiveActStructure() {
    }

    /**
     * 五段的章节边界, 每段为 [start, end]。
     * <p>
     * total >= 5 时保证连续覆盖 [1, total], 无空档无重叠;
     * total < 5 时章数不足以拆出五段, 后几段会合并到同一章, 但都钳制在 [1, total] 内, 不会越界。
     */
    public static int[][] bounds(int total) {
        int t = Math.max(1, total);
        int openEnd;
        int devEnd;
        int mid;
        int climax;
        if (t <= 1) {
            openEnd = devEnd = mid = climax = 1;
        } else if (t <= 4) {
            openEnd = 1;
            devEnd = Math.min(2, t);
            mid = Math.min(3, t);
            climax = Math.min(4, t);
        } else {
            // 预留 中点 1 章 + 高潮 1 章 + 结局至少 1 章, 其余分给开端与发展
            openEnd = clamp(Math.round(t * 0.2f), 1, t - 4);
            devEnd = clamp(openEnd + Math.max(1, Math.round(t * 0.3f)), openEnd + 1, t - 3);
            mid = devEnd + 1;
            climax = mid + 1;
        }
        int finaleStart = t <= 4 ? climax : climax + 1;
        return new int[][]{
                {1, openEnd},
                {openEnd + 1, devEnd},
                {mid, mid},
                {climax, climax},
                {finaleStart, t}
        };
    }

    /** 五段区间字符串 ("1-4" / "5"), 供大纲师 prompt 直接使用 */
    public static String[] ranges(int total) {
        int[][] b = bounds(total);
        String[] out = new String[b.length];
        for (int i = 0; i < b.length; i++) {
            out[i] = b[i][0] == b[i][1] ? String.valueOf(b[i][0]) : b[i][0] + "-" + b[i][1];
        }
        return out;
    }

    /** 给出章号所属的阶段索引 (1-5), 章号越界时钳制到首段/末段 */
    public static int phaseOf(int chapter, int total) {
        int[][] b = bounds(total);
        for (int i = 0; i < b.length; i++) {
            if (chapter >= b[i][0] && chapter <= b[i][1]) {
                return i + 1;
            }
        }
        return chapter < 1 ? 1 : 5;
    }

    /** 阶段索引 (1-5) 对应的中文名称, 越界返回空串 */
    public static String phaseName(int phaseIndex) {
        if (phaseIndex < 1 || phaseIndex > PHASE_NAMES.length) {
            return "";
        }
        return PHASE_NAMES[phaseIndex - 1];
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
