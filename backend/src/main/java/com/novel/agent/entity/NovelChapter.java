package com.novel.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 小说章节 (PostgreSQL)
 * 每章正文 + 本章大纲快照 + 审核结论
 */
@Data
@TableName("t_novel_chapter")
public class NovelChapter {

    /** 自增 ID: 转为字符串, 避免前端 JS 精度丢失 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Integer chapterNo;

    private String title;

    /** 本章大纲快照, 审核员据此比对正文是否偏离 */
    private String outline;

    /** 正文 */
    private String content;

    /** 本章摘要, 供后续章节作为前情 */
    private String summary;

    /** 审核是否通过 */
    private Boolean reviewPassed;

    /** 审核问题 JSON */
    private String reviewIssues;

    /** 重写超限仍未通过时置 true */
    private Boolean needsReview;

    /** 重写轮次 */
    private Integer rewriteRounds;

    private Integer wordCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
