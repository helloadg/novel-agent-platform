package com.novel.agent.controller;

import com.novel.agent.common.Result;
import com.novel.agent.entity.ModelConfig;
import com.novel.agent.entity.ModelRoleBinding;
import com.novel.agent.llm.ActiveModelProvider;
import com.novel.agent.llm.ModelConfigService;
import com.novel.agent.llm.ModelConnectionTester;
import com.novel.agent.mapper.ModelRoleBindingMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模型配置接口
 * <p>
 * 页面可维护多个 OpenAI 兼容接入点 (URL / API Key / 模型名), 同一时间只有一条 active,
 * 启用后 RAG 与小说智能体立即切换, 无需重启。
 */
@Slf4j
@RestController
@RequestMapping("/model-config")
@RequiredArgsConstructor
public class ModelConfigController {

    private final ModelConfigService configService;
    private final ModelConnectionTester connectionTester;
    private final ActiveModelProvider modelProvider;
    private final ModelRoleBindingMapper bindingMapper;

    @GetMapping
    public Result<List<ModelConfig>> list() {
        return Result.success(configService.list());
    }

    @PostMapping
    public Result<ModelConfig> create(@RequestBody ModelConfig request) {
        String invalid = validate(request);
        if (invalid != null) {
            return Result.fail(invalid);
        }
        ModelConfig created = configService.create(request);
        if (Boolean.TRUE.equals(request.getActive())) {
            configService.activate(created.getId());
            created = configService.get(created.getId());
        }
        modelProvider.invalidate();
        log.info("[model-config] 新增配置 id={} name={} model={}",
                created.getId(), created.getName(), created.getModelName());
        return Result.success(created);
    }

    @PutMapping("/{id}")
    public Result<ModelConfig> update(@PathVariable Long id, @RequestBody ModelConfig request) {
        String invalid = validate(request);
        if (invalid != null) {
            return Result.fail(invalid);
        }
        ModelConfig updated = configService.update(id, request);
        if (updated == null) {
            return Result.fail("配置不存在");
        }
        modelProvider.invalidate();
        log.info("[model-config] 更新配置 id={} name={} model={}",
                updated.getId(), updated.getName(), updated.getModelName());
        return Result.success(updated);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        if (!configService.delete(id)) {
            return Result.fail("配置不存在");
        }
        modelProvider.invalidate();
        log.info("[model-config] 删除配置 id={}", id);
        return Result.success();
    }

    /** 启用该配置作为当前模型 */
    @PostMapping("/{id}/activate")
    public Result<ModelConfig> activate(@PathVariable Long id) {
        if (!configService.activate(id)) {
            return Result.fail("配置不存在");
        }
        modelProvider.invalidate();
        log.info("[model-config] 启用配置 id={}", id);
        return Result.success(configService.get(id));
    }

    /**
     * 连通性测试: 可以直接测表单里未保存的值; 传 id 时用库里已存的配置, 表单字段可覆盖
     */
    @PostMapping("/test")
    public Result<ModelConnectionTester.TestResult> test(@RequestBody ModelConfig request) {
        ModelConfig config = request;
        if (request.getId() != null) {
            ModelConfig stored = configService.get(request.getId());
            if (stored != null) {
                config = new ModelConfig();
                config.setId(stored.getId());
                config.setBaseUrl(blank(request.getBaseUrl()) ? stored.getBaseUrl() : request.getBaseUrl());
                config.setApiKey(blank(request.getApiKey()) ? stored.getApiKey() : request.getApiKey());
                config.setModelName(blank(request.getModelName()) ? stored.getModelName() : request.getModelName());
                config.setTemperature(request.getTemperature() == null ? stored.getTemperature() : request.getTemperature());
            }
        }
        // 测试只关心接入参数, 不要求配置名称 (编辑态合并出的对象没有 name)
        String invalid = validateEndpoint(config);
        if (invalid != null) {
            return Result.fail(invalid);
        }
        return Result.success(connectionTester.test(config.getBaseUrl(), config.getApiKey(),
                config.getModelName(), config.getTemperature()));
    }

    private static String validate(ModelConfig config) {
        if (config == null) {
            return "请求体不能为空";
        }
        if (blank(config.getName())) {
            return "配置名称不能为空";
        }
        return validateEndpoint(config);
    }

    /** 接入参数校验: 新增/修改/测试共用 */
    private static String validateEndpoint(ModelConfig config) {
        if (config == null) {
            return "请求体不能为空";
        }
        if (blank(config.getBaseUrl())) {
            return "模型地址不能为空";
        }
        if (!config.getBaseUrl().trim().startsWith("http")) {
            return "模型地址需以 http:// 或 https:// 开头";
        }
        if (blank(config.getApiKey())) {
            return "API Key 不能为空";
        }
        if (blank(config.getModelName())) {
            return "模型名称不能为空";
        }
        return null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    // ==================== 角色-模型绑定 ====================

    /** 列出所有角色绑定 (按 agent_role 唯一) */
    @GetMapping("/bindings")
    public Result<List<ModelRoleBinding>> listBindings() {
        return Result.success(bindingMapper.selectList(null));
    }

    /** 保存/更新绑定 (upsert by agent_role) */
    @PostMapping("/bindings")
    public Result<ModelRoleBinding> saveBinding(@RequestBody ModelRoleBinding binding) {
        if (blank(binding.getAgentRole()) || binding.getModelConfigId() == null) {
            return Result.fail("agentRole 和 modelConfigId 必填");
        }
        ModelRoleBinding existing = bindingMapper.selectOne(
                new LambdaQueryWrapper<ModelRoleBinding>()
                        .eq(ModelRoleBinding::getAgentRole, binding.getAgentRole()));
        if (existing != null) {
            existing.setModelConfigId(binding.getModelConfigId());
            existing.setProfile(blank(binding.getProfile()) ? "balanced" : binding.getProfile());
            bindingMapper.updateById(existing);
            modelProvider.invalidate();
            return Result.success(existing);
        }
        binding.setProfile(blank(binding.getProfile()) ? "balanced" : binding.getProfile());
        bindingMapper.insert(binding);
        modelProvider.invalidate();
        return Result.success(binding);
    }

    /** 批量保存 (前端一次性提交所有角色绑定) */
    @PostMapping("/bindings/batch")
    public Result<Integer> saveBindings(@RequestBody List<ModelRoleBinding> bindings) {
        int count = 0;
        for (ModelRoleBinding b : bindings) {
            if (blank(b.getAgentRole()) || b.getModelConfigId() == null) continue;
            ModelRoleBinding existing = bindingMapper.selectOne(
                    new LambdaQueryWrapper<ModelRoleBinding>()
                            .eq(ModelRoleBinding::getAgentRole, b.getAgentRole()));
            if (existing != null) {
                existing.setModelConfigId(b.getModelConfigId());
                existing.setProfile(blank(b.getProfile()) ? "balanced" : b.getProfile());
                bindingMapper.updateById(existing);
            } else {
                b.setProfile(blank(b.getProfile()) ? "balanced" : b.getProfile());
                bindingMapper.insert(b);
            }
            count++;
        }
        modelProvider.invalidate();
        return Result.success(count);
    }

    /** 删除某个角色绑定 */
    @DeleteMapping("/bindings/{agentRole}")
    public Result<Void> deleteBinding(@PathVariable String agentRole) {
        bindingMapper.delete(new LambdaUpdateWrapper<ModelRoleBinding>()
                .eq(ModelRoleBinding::getAgentRole, agentRole));
        modelProvider.invalidate();
        return Result.success();
    }
}
