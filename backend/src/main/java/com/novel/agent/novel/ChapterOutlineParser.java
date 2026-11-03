package com.novel.agent.novel;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分章大纲解析器
 * <p>
 * 大纲师按固定 Markdown 格式输出 ("## 第N章 标题" + 要点列表), 本类负责按章切分。
 * 解析失败时不抛异常, 由调用方降级为"大纲全文 + 本章提示"。
 */
public final class ChapterOutlineParser {

    /**
     * 章标题行: 允许 1~6 级标题, 允许 "第1章" / "【第1章】" / "第 1 章: 标题" 等写法
     */
    private static final Pattern HEADING = Pattern.compile(
            "(?m)^\\s*(?:#{1,6}\\s*)?[【\\[]?第\\s*(\\d+)\\s*章\\s*[】\\]]?\\s*[:：、.\\-—]?\\s*(.*)$");

    /** 标题行最长容忍长度, 超过则判定为正文中的普通句子而非标题 */
    private static final int MAX_TITLE_LENGTH = 40;

    /** 分卷标题行 (如 "## 第2卷 分章大纲"), 不属于任何一章的正文 */
    private static final Pattern VOLUME_HEADING = Pattern.compile(
            "^\\s*(?:#{1,6}\\s*)?[【\\[]?第\\s*(?:\\d+|[一二三四五六七八九十百千]+)\\s*卷");

    private ChapterOutlineParser() {
    }

    /** 单章大纲条目 */
    public static class Entry implements Serializable {
        private final int chapterNo;
        private final String title;
        private final String body;

        Entry(int chapterNo, String title, String body) {
            this.chapterNo = chapterNo;
            this.title = title;
            this.body = body;
        }

        public int getChapterNo() {
            return chapterNo;
        }

        public String getTitle() {
            return title;
        }

        /** 该章标题行之后的要点内容 (不含标题行本身) */
        public String getBody() {
            return body;
        }

        /** 供 prompt 使用的完整片段 (标题行 + 要点) */
        public String toBlock() {
            return "第" + chapterNo + "章 " + title + "\n" + body;
        }
    }

    /** 按章标题切分, 没有任何可识别章标题时返回空列表 */
    public static List<Entry> parse(String outline) {
        List<Entry> entries = new ArrayList<>();
        if (outline == null || outline.isBlank()) {
            return entries;
        }
        String[] lines = outline.split("\n", -1);
        int currentNo = -1;
        String currentTitle = "";
        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            Matcher matcher = HEADING.matcher(line);
            if (matcher.matches() && matcher.group(2).trim().length() <= MAX_TITLE_LENGTH) {
                if (currentNo > 0) {
                    entries.add(new Entry(currentNo, currentTitle, body.toString().trim()));
                }
                currentNo = Integer.parseInt(matcher.group(1));
                currentTitle = matcher.group(2).trim();
                body.setLength(0);
            } else if (currentNo > 0) {
                // 卷标题行是章节之间的分隔, 不能并到上一章 body, 否则第 N 卷开头会污染上一章大纲
                if (VOLUME_HEADING.matcher(line).matches()) {
                    continue;
                }
                body.append(line).append('\n');
            }
        }
        if (currentNo > 0) {
            entries.add(new Entry(currentNo, currentTitle, body.toString().trim()));
        }
        return entries;
    }

    /** 取第 N 章条目 */
    public static Optional<Entry> chapterAt(String outline, int chapterNo) {
        return parse(outline).stream()
                .filter(e -> e.getChapterNo() == chapterNo)
                .findFirst();
    }

    /** 首个章标题之前的整体结构 (三幕总纲等) */
    public static String overview(String outline) {
        if (outline == null || outline.isBlank()) {
            return "";
        }
        String[] lines = outline.split("\n", -1);
        StringBuilder overview = new StringBuilder();
        for (String line : lines) {
            Matcher matcher = HEADING.matcher(line);
            if (matcher.matches() && matcher.group(2).trim().length() <= MAX_TITLE_LENGTH) {
                break;
            }
            overview.append(line).append('\n');
        }
        return overview.toString().trim();
    }

    /** 取与目标章相邻的上下章条目 (用于给写手提供前后文脉络) */
    public static Optional<Entry> neighbour(List<Entry> entries, int chapterNo, int offset) {
        int target = chapterNo + offset;
        return entries.stream().filter(e -> e.getChapterNo() == target).findFirst();
    }
}
