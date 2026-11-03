package com.novel.agent.llm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.novel.agent.entity.ModelConfig;
import com.novel.agent.mapper.ModelConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 模型配置持久化服务 (PostgreSQL)
 * <p>
 * 只负责数据存取与校验, 不构建模型实例; 模型的热切换由 {@link ActiveModelProvider} 完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelConfigService {

    private final ModelConfigMapper mapper;

    public List<ModelConfig> list() {
        return mapper.selectList(new LambdaQueryWrapper<ModelConfig>()
                .orderByDesc(ModelConfig::getActive)
                .orderByDesc(ModelConfig::getCreateTime));
    }

    public ModelConfig get(Long id) {
        return id == null ? null : mapper.selectById(id);
    }

    /** 当前启用的配置; 表未建好或查询异常时返回 null (调用方回退 yml 配置) */
    public ModelConfig getActive() {
        return mapper.selectOne(new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getActive, true)
                .orderByDesc(ModelConfig::getUpdateTime)
                .last("LIMIT 1"));
    }

    @Transactional
    public ModelConfig create(ModelConfig input) {
        input.setId(null);
        input.setActive(input.getActive() != null && input.getActive());
        normalize(input);
        mapper.insert(input);
        // 第一条配置默认启用, 省去用户多点一次
        if (!input.getActive() && getActive() == null) {
            activate(input.getId());
        }
        return mapper.selectById(input.getId());
    }

    @Transactional
    public ModelConfig update(Long id, ModelConfig input) {
        ModelConfig exists = mapper.selectById(id);
        if (exists == null) {
            return null;
        }
        normalize(input);
        mapper.update(null, new LambdaUpdateWrapper<ModelConfig>()
                .set(ModelConfig::getName, input.getName())
                .set(ModelConfig::getBaseUrl, input.getBaseUrl())
                .set(ModelConfig::getApiKey, input.getApiKey())
                .set(ModelConfig::getModelName, input.getModelName())
                .set(ModelConfig::getTemperature, input.getTemperature())
                .set(ModelConfig::getTimeoutSeconds, input.getTimeoutSeconds())
                .set(ModelConfig::getMaxTokens, input.getMaxTokens())
                .set(ModelConfig::getUpdateTime, LocalDateTime.now())
                .eq(ModelConfig::getId, id));
        return mapper.selectById(id);
    }

    /** 启用某条配置: 先清掉其他启用项, 保证全局只有一条 active */
    @Transactional
    public boolean activate(Long id) {
        if (mapper.selectById(id) == null) {
            return false;
        }
        mapper.update(null, new LambdaUpdateWrapper<ModelConfig>()
                .set(ModelConfig::getActive, false)
                .set(ModelConfig::getUpdateTime, LocalDateTime.now())
                .eq(ModelConfig::getActive, true));
        mapper.update(null, new LambdaUpdateWrapper<ModelConfig>()
                .set(ModelConfig::getActive, true)
                .set(ModelConfig::getUpdateTime, LocalDateTime.now())
                .eq(ModelConfig::getId, id));
        return true;
    }

    @Transactional
    public boolean delete(Long id) {
        return mapper.deleteById(id) > 0;
    }

    /** 字段清洗: 去空格, baseUrl 去掉末尾斜杠, 补默认值 */
    private void normalize(ModelConfig config) {
        config.setName(trim(config.getName()));
        String baseUrl = trim(config.getBaseUrl());
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        config.setBaseUrl(baseUrl);
        config.setApiKey(trim(config.getApiKey()));
        config.setModelName(trim(config.getModelName()));
        if (config.getTemperature() == null) {
            config.setTemperature(0.7);
        }
        if (config.getTimeoutSeconds() == null || config.getTimeoutSeconds() <= 0) {
            config.setTimeoutSeconds(120);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
