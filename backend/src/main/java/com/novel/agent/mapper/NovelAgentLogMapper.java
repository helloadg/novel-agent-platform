package com.novel.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.agent.entity.NovelAgentLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 智能体协同日志 Mapper (PostgreSQL)
 */
@Mapper
public interface NovelAgentLogMapper extends BaseMapper<NovelAgentLog> {
}
