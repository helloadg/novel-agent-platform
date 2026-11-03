package com.novel.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novel.agent.entity.NovelChapter;
import org.apache.ibatis.annotations.Mapper;

/**
 * 小说章节 Mapper (PostgreSQL)
 */
@Mapper
public interface NovelChapterMapper extends BaseMapper<NovelChapter> {
}
