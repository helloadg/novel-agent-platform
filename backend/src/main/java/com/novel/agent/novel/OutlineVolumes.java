package com.novel.agent.novel;

/**
 * 分卷划分: 每 {@link #VOLUME_SIZE} 章为一卷, 末卷按剩余章节数收敛。
 * <p>
 * 长篇不一次性生成全部细纲, 而是"全局架构 + 当前卷细纲", 写完本卷再生成下一卷。
 */
public final class OutlineVolumes {

    /** 每卷章节数 */
    public static final int VOLUME_SIZE = 20;

    private OutlineVolumes() {
    }

    /** 总卷数 (至少 1 卷) */
    public static int count(int totalChapters) {
        int total = Math.max(1, totalChapters);
        return (total + VOLUME_SIZE - 1) / VOLUME_SIZE;
    }

    /** 章号所属卷号 (从 1 开始) */
    public static int volumeOf(int chapterNo) {
        int no = Math.max(1, chapterNo);
        return (no - 1) / VOLUME_SIZE + 1;
    }

    /** 该卷起始章号 */
    public static int firstChapter(int volumeNo) {
        return (Math.max(1, volumeNo) - 1) * VOLUME_SIZE + 1;
    }

    /** 该卷结束章号 (末卷不超过总章数) */
    public static int lastChapter(int volumeNo, int totalChapters) {
        return Math.min(Math.max(1, volumeNo) * VOLUME_SIZE, Math.max(1, totalChapters));
    }

    /** 第 volumeNo 卷写完后的细纲覆盖章号 */
    public static int horizonAfter(int volumeNo, int totalChapters) {
        return lastChapter(volumeNo, totalChapters);
    }
}
