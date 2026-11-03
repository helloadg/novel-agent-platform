package com.novel.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色-模型绑定: 每个 Agent 角色可独立选择使用哪个模型配置
 */
@Data
@TableName("t_model_role_binding")
public class ModelRoleBinding {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色标识: world-builder / outline-architect / chapter-writer / chapter-polisher / consistency-reviewer / summary */
    private String agentRole;

    /** 关联 t_model_config.id */
    private Long modelConfigId;

    /** 档位: creative / balanced / strict */
    private String profile;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
