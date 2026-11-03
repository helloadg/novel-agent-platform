package com.novel.agent.novel;

import com.novel.agent.novel.model.WritingConsole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 每章 Prompt 的上下文预算组装
 * <p>
 * 单次写作调用会带上设定 / 大纲 / 前情 / 写作控制台 四块内容, 不加约束时容易超出模型上下文或稀释重点,
 * 因此这里统一做"保留首尾 + 中间省略"与条数限制。
 */
@Component
@RequiredArgsConstructor
public class NovelContextBuilder {

    private final NovelProperties properties;

    /**
     * 设定块: 世界观(含规则体系) + 人物, 两块各自独立限长。
     * 不能合并后再截断: 人物在后半段, 合并截断会把中间若干人物整段丢掉, 导致写手凭空漏人。
     */
    public String worldviewBlock(String worldview, String characters) {
        String world = truncateMiddle(safe(worldview), properties.getContext().getWorldviewMaxChars());
        String people = headLines(safe(characters), properties.getContext().getCharactersMaxChars());
        return people.isBlank() ? world : world + "\n\n" + people;
    }

    /** 大纲块: 整体结构 + 前后各一章 + 本章完整条目; 无法切分时降级为大纲全文 */
    public String outlineBlock(String outline, int chapterNo) {
        List<ChapterOutlineParser.Entry> entries = ChapterOutlineParser.parse(outline);
        if (entries.isEmpty()) {
            return "（大纲未按标准分章格式书写, 以下是完整大纲, 请自行定位第 " + chapterNo + " 章）\n"
                    + truncateMiddle(safe(outline), 2000);
        }
        StringBuilder sb = new StringBuilder();
        String overview = ChapterOutlineParser.overview(outline);
        if (!overview.isBlank()) {
            sb.append("【全局故事架构 (含主线与结局锚点, 全文必须朝此收束)】\n")
                    .append(truncateMiddle(overview, 1200)).append("\n\n");
        }
        ChapterOutlineParser.neighbour(entries, chapterNo, -1)
                .ifPresent(e -> sb.append("【上一章大纲】\n").append(truncateMiddle(e.toBlock(), 400)).append("\n\n"));
        sb.append("【本章大纲（必须严格遵守）】\n");
        ChapterOutlineParser.chapterAt(outline, chapterNo)
                .ifPresentOrElse(e -> sb.append(e.toBlock()),
                        () -> sb.append("第 ").append(chapterNo).append(" 章（大纲中未单列, 请依据整体结构推进剧情）"));
        sb.append('\n');
        ChapterOutlineParser.neighbour(entries, chapterNo, 1)
                .ifPresent(e -> sb.append("\n【下一章大纲（仅供伏笔, 不要提前写完）】\n")
                        .append(truncateMiddle(e.toBlock(), 300)));
        return sb.toString();
    }

    /** 前情块: 滚动总纲 + 最近若干章摘要 + 上一章结尾片段 */
    public String previousBlock(String rollingSummary, List<String> chapterSummaries, String previousTail) {
        StringBuilder sb = new StringBuilder();
        if (rollingSummary != null && !rollingSummary.isBlank()) {
            sb.append("【剧情总纲】\n").append(truncateMiddle(rollingSummary, 500)).append("\n\n");
        }
        if (chapterSummaries != null && !chapterSummaries.isEmpty()) {
            int count = Math.max(1, properties.getContext().getRecentSummaryCount());
            int from = Math.max(0, chapterSummaries.size() - count);
            sb.append("【最近章节摘要】\n");
            for (String summary : chapterSummaries.subList(from, chapterSummaries.size())) {
                sb.append("- ").append(truncateMiddle(safe(summary), 300)).append('\n');
            }
            sb.append('\n');
        }
        if (previousTail != null && !previousTail.isBlank()) {
            sb.append("【上一章结尾片段（保持文风与悬念衔接）】\n")
                    .append(tail(previousTail, properties.getContext().getTailChars()));
        }
        return sb.toString().trim();
    }

    /**
     * 写作控制台块: 过滤出当前章节"应该关心"的暗线 (setupChapter <= current <= resolveChapter)、
     * 待收伏笔 (resolveChapter == current 或 current 在 setupChapter 附近)、本章节奏标签。
     * 控制台 JSON 解析失败时降级为空串, 写手走旧路径不报错。
     */
    public String consoleBlock(String consoleJson, int chapterNo, int totalChapters) {
        Optional<WritingConsole> opt = StructuredOutputParser.parse(consoleJson, WritingConsole.class);
        if (opt.isEmpty()) {
            return "";
        }
        WritingConsole console = opt.get();
        StringBuilder sb = new StringBuilder();

        sb.append("【写作控制台 (显式锚点, 写作时必须遵守)】\n");

        // 主线阶段 + 节奏标签
        if (console.getPhase() != null) {
            sb.append("当前阶段: ").append(console.getPhase()).append('\n');
        }
        if (console.getMainLineGoal() != null) {
            sb.append("主线目标: ").append(console.getMainLineGoal()).append('\n');
        }
        if (console.getPhaseGoal() != null && !console.getPhaseGoal().isBlank()) {
            sb.append("本阶段应推进到: ").append(console.getPhaseGoal()).append('\n');
        }
        // 节奏标签: 优先取本章的逐章覆盖值, 否则回退全局默认值
        String rhythm = null;
        if (console.getRhythmByChapter() != null) {
            rhythm = console.getRhythmByChapter().get(String.valueOf(chapterNo));
        }
        if (rhythm == null || rhythm.isBlank()) {
            rhythm = console.getRhythmTag();
        }
        if (rhythm != null && !rhythm.isBlank()) {
            sb.append("本章节奏标签: ").append(rhythm).append('\n');
        }

        // 活跃暗线: setup 之后且未回收; 到了兑现章要明确要求收束, 不能继续吊着
        List<WritingConsole.Thread> active = console.getActiveThreads();
        if (active != null && !active.isEmpty()) {
            StringBuilder threadBlock = new StringBuilder();
            for (WritingConsole.Thread t : active) {
                boolean relevant = t.getSetupChapter() != null && t.getResolveChapter() != null
                        && t.getSetupChapter() <= chapterNo && !"resolved".equals(t.getStatus());
                if (!relevant) continue;
                threadBlock.append("- ").append(t.getName()).append(" (第").append(t.getSetupChapter())
                        .append("章埋钉, 第").append(t.getResolveChapter()).append("章兑现)");
                if (t.getCurrentProgress() != null) threadBlock.append(" · 当前进展: ").append(t.getCurrentProgress());
                threadBlock.append('\n');
                if (t.getHint() != null) {
                    threadBlock.append("  悬念提示: ").append(t.getHint()).append('\n');
                }
                if (chapterNo >= t.getResolveChapter()) {
                    threadBlock.append("  本章应兑现或收束该暗线, 不要再继续吊着\n");
                } else {
                    threadBlock.append("  必须持续吊着, 不能提前揭穿\n");
                }
            }
            if (!threadBlock.isEmpty()) {
                sb.append("\n【活跃暗线】\n").append(threadBlock);
            }
        }

        // 待收伏笔 (resolveChapter == chapterNo 或 setupChapter 刚过)
        List<WritingConsole.Foreshadow> pending = console.getPendingForeshadows();
        if (pending != null && !pending.isEmpty()) {
            StringBuilder foreshadowBlock = new StringBuilder();
            for (WritingConsole.Foreshadow f : pending) {
                if (f.getSetupChapter() == null || f.getResolveChapter() == null) continue;
                if ("resolved".equals(f.getStatus()) || "discarded".equals(f.getStatus())) continue;
                String note;
                if (chapterNo == f.getSetupChapter()) {
                    note = "本章应埋设该伏笔, 读者不会立刻懂, 后续章节会有回报";
                } else if (chapterNo == f.getResolveChapter()) {
                    note = "本章应兑现该伏笔, 把之前埋下的钩子拉出来给读者";
                } else if (chapterNo > f.getResolveChapter()) {
                    note = "已过计划回收章节, 若正文尚未兑现请尽快补收";
                } else if (chapterNo > f.getSetupChapter()) {
                    note = "伏笔已埋设, 不要让主角忘记它的存在, 偶尔给一下镜头";
                } else {
                    note = "伏笔尚未到埋设时机, 不要提前写";
                }
                foreshadowBlock.append("- ").append(f.getDescription())
                        .append(" (埋: 第").append(f.getSetupChapter())
                        .append("章 · 收: 第").append(f.getResolveChapter()).append("章)")
                        .append("\n  ").append(note).append('\n');
            }
            if (!foreshadowBlock.isEmpty()) {
                sb.append("\n【伏笔清单 (该收的收, 还没到的继续吊着)】\n").append(foreshadowBlock);
            }
        }

        // 明确禁止: 优先取本章的逐章覆盖值, 否则回退全局默认值
        List<String> avoid = console.getAvoidByChapter() == null
                ? null : console.getAvoidByChapter().get(String.valueOf(chapterNo));
        if (avoid == null || avoid.isEmpty()) {
            avoid = console.getAvoidThisChapter();
        }
        if (avoid != null && !avoid.isEmpty()) {
            sb.append("\n【本章绝对不能发生】\n");
            for (String a : avoid) sb.append("- ").append(a).append('\n');
        }

        return sb.toString().trim();
    }

    /** 保留首尾、中间省略 */
    public static String truncateMiddle(String text, int max) {
        String value = safe(text);
        if (value.length() <= max) {
            return value;
        }
        int head = max * 2 / 3;
        int tailLen = max - head - 5;
        return value.substring(0, head) + "\n...(略)...\n" + value.substring(value.length() - tailLen);
    }

    /** 保留开头 max 字, 在换行处断开 (人物清单用, 宁可少给后面的人物, 也不要把中间人物截成半句) */
    public static String headLines(String text, int max) {
        String value = safe(text);
        if (value.length() <= max) {
            return value;
        }
        String head = value.substring(0, max);
        int lastBreak = head.lastIndexOf('\n');
        return (lastBreak > max / 2 ? head.substring(0, lastBreak) : head).trim() + "\n...(其余人物略, 请按已有设定推断)";
    }

    /** 取结尾片段 */
    public static String tail(String text, int max) {
        String value = safe(text);
        return value.length() <= max ? value : value.substring(value.length() - max);
    }

    private static String safe(String text) {
        return text == null ? "" : text.trim();
    }
}
