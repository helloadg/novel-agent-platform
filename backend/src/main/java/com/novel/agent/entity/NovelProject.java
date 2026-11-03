package com.novel.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 小说项目 (PostgreSQL)
 * 一部作品一行, 承载设定/大纲/滚动摘要与整体生成进度
 */
@Data
@TableName("t_novel_project")
public class NovelProject {

    /** 自增 ID: 超出 JS Number 安全整数范围, 序列化为字符串避免前端丢精度 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 作品名 */
    private String title;

    /** 题材 (玄幻/推理/科幻...) */
    private String genre;

    /** 归属用户 */
    private String userId;

    /** 用户录入的想法或初始大纲 */
    private String userIdea;

    /** 世界观 + 规则体系 (设定师产出) */
    private String worldview;

    /** 主要人物小传 (设定师产出) */
    private String characters;

    /** 分章大纲 (Markdown, 用户可编辑) */
    private String outline;

    /**
     * 写作控制台 JSON: 主线阶段 / 暗线清单 / 伏笔追踪 / 节奏标签
     * 大纲师初始化产出, 摘要员每章增量更新; 供写手和审核员显式把控主线/暗线/伏笔/节奏
     */
    private String writingConsole;

    /** 滚动剧情总纲, 每章增量更新 */
    private String rollingSummary;

    /** DRAFT / OUTLINING / OUTLINE_READY / WRITING / COMPLETED / FAILED */
    private String status;

    /** 当前执行到的图节点, 供前端展示进度 */
    private String stage;

    private Integer totalChapters;

    /** 已完成到第几章 */
    private Integer currentChapter;

    /** 失败原因 */
    private String errorMsg;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
