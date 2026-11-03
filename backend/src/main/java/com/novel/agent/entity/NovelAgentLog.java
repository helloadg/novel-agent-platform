package com.novel.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能体协同日志 (PostgreSQL)
 * 每个图节点执行一条, 供前端展示多智能体协同过程
 */
@Data
@TableName("t_novel_agent_log")
public class NovelAgentLog {

    /** 自增 ID: 转为字符串, 避免前端 JS 精度丢失 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Integer chapterNo;

    /** 智能体标识 (如 world_builder / chapter_writer) */
    private String agentName;

    /** 阶段描述 */
    private String stage;

    private String inputSummary;

    /** 输出摘要, 截断存储不存正文副本 */
    private String outputText;

    private Integer elapsedMs;

    private LocalDateTime createTime;
}
