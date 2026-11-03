package com.novel.agent.controller;

import com.novel.agent.common.Result;
import com.novel.agent.entity.NovelAgentLog;
import com.novel.agent.entity.NovelChapter;
import com.novel.agent.entity.NovelProject;
import com.novel.agent.novel.NovelGenre;
import com.novel.agent.novel.NovelGraphRunner;
import com.novel.agent.novel.NovelProjectService;
import com.novel.agent.novel.NovelStatus;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 小说写作工坊接口
 * <p>
 * 生成过程是后台异步的, 前端通过 GET /novel/project/{id}/status 轮询进度。
 */
@Slf4j
@RestController
@RequestMapping("/novel")
@RequiredArgsConstructor
public class NovelController {

    /** 未指定总章数时的默认值 */
    private static final int DEFAULT_TOTAL_CHAPTERS = 3;
    /** 总章数上限, 防止误传超大值导致长时间写作 */
    private static final int MAX_TOTAL_CHAPTERS = 200;
    /** 状态接口返回的协同日志条数上限 */
    private static final int STATUS_LOG_LIMIT = 20;

    private final NovelProjectService projectService;
    private final NovelGraphRunner graphRunner;

    /** 题材列表 (后端枚举为单一事实源) */
    @GetMapping("/genres")
    public Result<List<Map<String, String>>> genres() {
        return Result.success(Arrays.stream(NovelGenre.values())
                .map(genre -> Map.of("code", genre.name(),
                        "label", genre.getLabel(),
                        "description", genre.getDescription()))
                .toList());
    }

    /** 创建作品并异步生成世界观/人物/大纲 */
    @PostMapping("/project")
    public Result<Map<String, Object>> create(@RequestBody CreateRequest request,
                                              @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            return Result.fail("作品名不能为空");
        }
        NovelGenre genre = NovelGenre.of(request.getGenre());
        NovelProject project = projectService.create(request.getTitle().trim(), genre, userId,
                request.getUserIdea(), normalizeTotalChapters(request.getTotalChapters()));
        boolean started = projectService.casStatus(project.getId(),
                Set.of(NovelStatus.DRAFT.name()), NovelStatus.OUTLINING, "开始生成世界观与人物设定");
        if (started) {
            graphRunner.startOutline(project.getId());
        }
        log.info("[novel] 创建作品 projectId={} title={} genre={} started={}",
                project.getId(), project.getTitle(), genre.name(), started);
        // 雪花 ID 转字符串, 避免前端 JS 精度丢失
        return Result.success(Map.of("projectId", String.valueOf(project.getId())));
    }

    /** 历史作品分页 */
    @GetMapping("/project/page")
    public Result<List<NovelProject>> page(@RequestParam(defaultValue = "1") long page,
                                           @RequestParam(defaultValue = "10") long size,
                                           @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return Result.success(projectService.pageProjects(page, size, userId));
    }

    /** 作品详情: 设定 / 大纲 / 章节概览 */
    @GetMapping("/project/{id}")
    public Result<ProjectDetailVO> detail(@PathVariable Long id) {
        NovelProject project = projectService.getProject(id);
        if (project == null) {
            return Result.fail("作品不存在");
        }
        ProjectDetailVO vo = new ProjectDetailVO();
        vo.setId(project.getId());
        vo.setTitle(project.getTitle());
        vo.setGenre(project.getGenre());
        vo.setGenreLabel(NovelGenre.of(project.getGenre()).getLabel());
        vo.setUserId(project.getUserId());
        vo.setUserIdea(project.getUserIdea());
        vo.setWorldview(project.getWorldview());
        vo.setCharacters(project.getCharacters());
        vo.setOutline(project.getOutline());
        vo.setWritingConsole(project.getWritingConsole());
        vo.setStatus(project.getStatus());
        vo.setStage(project.getStage());
        vo.setTotalChapters(project.getTotalChapters());
        vo.setCurrentChapter(project.getCurrentChapter());
        vo.setErrorMsg(project.getErrorMsg());
        vo.setCreateTime(project.getCreateTime());
        List<ChapterBriefVO> chapters = new ArrayList<>();
        for (NovelChapter chapter : projectService.listChapters(id)) {
            chapters.add(ChapterBriefVO.of(chapter));
        }
        vo.setChapters(chapters);
        return Result.success(vo);
    }

    /** 轮询进度: 状态 + 阶段 + 最近智能体协同日志 */
    @GetMapping("/project/{id}/status")
    public Result<StatusVO> status(@PathVariable Long id) {
        NovelProject project = projectService.getProject(id);
        if (project == null) {
            return Result.fail("作品不存在");
        }
        StatusVO vo = new StatusVO();
        vo.setStatus(project.getStatus());
        vo.setStage(project.getStage());
        vo.setTotalChapters(project.getTotalChapters());
        vo.setCurrentChapter(project.getCurrentChapter());
        vo.setErrorMsg(project.getErrorMsg());
        List<AgentLogVO> logs = new ArrayList<>();
        for (NovelAgentLog row : projectService.recentLogs(id, STATUS_LOG_LIMIT)) {
            logs.add(AgentLogVO.of(row));
        }
        vo.setLogs(logs);
        return Result.success(vo);
    }

    /** 保存用户修改后的大纲: 同时写库与图状态, 保证恢复执行时使用新大纲 */
    @PutMapping("/project/{id}/outline")
    public Result<Void> updateOutline(@PathVariable Long id, @RequestBody OutlineRequest request) {
        NovelProject project = projectService.getProject(id);
        if (project == null) {
            return Result.fail("作品不存在");
        }
        if (request.getOutline() == null || request.getOutline().isBlank()) {
            return Result.fail("大纲内容不能为空");
        }
        projectService.saveOutline(id, request.getOutline());
        graphRunner.updateOutlineState(id, request.getOutline());
        projectService.updateStage(id, "大纲已保存, 可开始写作");
        return Result.success();
    }

    /** 人工修改世界观与人物设定: 写库并同步图状态, 复审放行/重生成都以修改后的版本为准 */
    @PutMapping("/project/{id}/world")
    public Result<Void> updateWorld(@PathVariable Long id, @RequestBody WorldUpdateRequest request) {
        NovelProject project = projectService.getProject(id);
        if (project == null) {
            return Result.fail("作品不存在");
        }
        if (request.getWorldview() == null || request.getWorldview().isBlank()) {
            return Result.fail("世界观内容不能为空");
        }
        if (!NovelStatus.OUTLINING.name().equals(project.getStatus())) {
            return Result.fail("仅在世界观人工复审阶段可编辑");
        }
        String characters = request.getCharacters() == null ? "" : request.getCharacters();
        projectService.saveWorldview(id, request.getWorldview(), characters);
        graphRunner.updateWorldState(id, request.getWorldview(), characters);
        projectService.updateStage(id, "世界观与人物设定已保存, 等待人工复审");
        return Result.success();
    }

    /** 世界观/人物设定人工复审: 放行, 继续生成大纲 */
    @PostMapping("/project/{id}/world-approve")
    public Result<Void> worldApprove(@PathVariable Long id) {
        NovelProject project = projectService.getProject(id);
        if (project == null) {
            return Result.fail("作品不存在");
        }
        // 世界观复审只在 OUTLINING 状态有效 (图停在 await_world_review); 失败状态请走 retry-outline
        boolean ok = projectService.casStatus(id,
                Set.of(NovelStatus.OUTLINING.name()),
                NovelStatus.OUTLINING, "世界观设定已通过, 开始生成大纲");
        if (!ok) {
            return Result.fail("当前状态不支持世界观复审, 请刷新后重试");
        }
        // 放行前把库里的最新设定同步进图状态, 避免图里的旧稿 (尤其是历史数据里人物分节未拆开) 继续往下跑
        graphRunner.updateWorldState(id, project.getWorldview(), project.getCharacters());
        graphRunner.worldApprove(id);
        return Result.success();
    }

    /** 世界观/人物设定人工复审: 驳回, 提修改意见让设定师重生成 */
    @PostMapping("/project/{id}/world-rerun")
    public Result<Void> worldRerun(@PathVariable Long id,
                                   @RequestBody(required = false) WorldRerunRequest req) {
        NovelProject project = projectService.getProject(id);
        if (project == null) {
            return Result.fail("作品不存在");
        }
        boolean ok = projectService.casStatus(id,
                Set.of(NovelStatus.OUTLINING.name()),
                NovelStatus.OUTLINING, "人工驳回世界观设定, 重新生成中");
        if (!ok) {
            return Result.fail("当前状态不支持世界观复审, 请刷新后重试");
        }
        String hint = req == null ? null : req.hint;
        // 重生成时同样以库里最新设定作为"上一稿", 避免用图状态里的旧数据
        graphRunner.updateWorldState(id, project.getWorldview(), project.getCharacters());
        graphRunner.worldRerun(id, hint);
        return Result.success();
    }

    /** 分卷大纲人工复审: 放行, 继续写作 */
    @PostMapping("/project/{id}/volume-approve")
    public Result<Void> volumeApprove(@PathVariable Long id) {
        NovelProject project = projectService.getProject(id);
        if (project == null) {
            return Result.fail("作品不存在");
        }
        boolean ok = projectService.casStatus(id,
                Set.of(NovelStatus.WRITING.name()),
                NovelStatus.WRITING, "人工复审分卷大纲, 准备继续写作");
        if (!ok) {
            return Result.fail("当前状态不支持分卷大纲复审, 请刷新后重试");
        }
        graphRunner.volumeApprove(id);
        return Result.success();
    }

    /** 分卷大纲人工复审: 驳回, 提修改意见让分卷大纲师重生成 */
    @PostMapping("/project/{id}/volume-rerun")
    public Result<Void> volumeRerun(@PathVariable Long id,
                                    @RequestBody(required = false) VolumeRerunRequest req) {
        NovelProject project = projectService.getProject(id);
        if (project == null) {
            return Result.fail("作品不存在");
        }
        boolean ok = projectService.casStatus(id,
                Set.of(NovelStatus.WRITING.name()),
                NovelStatus.WRITING, "人工驳回分卷大纲, 重新生成中");
        if (!ok) {
            return Result.fail("当前状态不支持分卷大纲复审, 请刷新后重试");
        }
        String hint = req == null ? null : req.hint;
        graphRunner.volumeRerun(id, hint);
        return Result.success();
    }

    /** 大纲生成失败后的重试: 有检查点则续跑失败节点, 没有则从头重新生成 */
    @PostMapping("/project/{id}/retry-outline")
    public Result<Void> retryOutline(@PathVariable Long id) {
        NovelProject project = projectService.getProject(id);
        if (project == null) {
            return Result.fail("作品不存在");
        }
        if (project.getOutline() != null && !project.getOutline().isBlank()) {
            return Result.fail("大纲已存在, 请直接开始写作");
        }
        // 历史异常: 空大纲被写成 OUTLINE_READY (模型返回空内容), 也允许从该状态重新生成
        boolean outlineEmptyButReady = NovelStatus.OUTLINE_READY.name().equals(project.getStatus());
        boolean started = projectService.casStatus(id,
                Set.of(NovelStatus.FAILED.name(), NovelStatus.OUTLINE_READY.name()),
                NovelStatus.OUTLINING, "重试生成大纲中");
        if (!started) {
            return Result.fail("当前状态不支持重试大纲生成");
        }
        // OUTLINE_READY 说明图已停在 await_outline, 续跑会直接进入写作;
        // 必须从头重新生成大纲 (已有定稿设定会被设定师复用, 不会被覆盖)
        if (!outlineEmptyButReady && graphRunner.hasResumableCheckpoint(id)) {
            graphRunner.resumeOutline(id);
        } else {
            graphRunner.startOutline(id);
        }
        return Result.success();
    }

    /** 开始 / 继续逐章写作 */
    @PostMapping("/project/{id}/write")
    public Result<Void> write(@PathVariable Long id) {
        NovelProject project = projectService.getProject(id);
        if (project == null) {
            return Result.fail("作品不存在");
        }
        if (project.getOutline() == null || project.getOutline().isBlank()) {
            return Result.fail("请先生成或填写大纲");
        }
        boolean started = projectService.casStatus(id,
                Set.of(NovelStatus.OUTLINE_READY.name(), NovelStatus.FAILED.name()),
                NovelStatus.WRITING, "开始逐章写作");
        if (!started) {
            return Result.fail("该作品正在处理中, 请稍后再试");
        }
        graphRunner.resumeWriting(id);
        return Result.success();
    }

    /** 硬门禁触发后的人工干预: 强行放行当前章, 继续正常写作流程 */
    @PostMapping("/project/{id}/manual-approve")
    public Result<Void> manualApprove(@PathVariable Long id,
                                      @RequestBody(required = false) ManualApproveRequest req) {
        NovelProject project = projectService.getProject(id);
        if (project == null) {
            return Result.fail("作品不存在");
        }
        // 先确认图确实停在章节人工复审中断点, 再改数据库状态;
        // 否则 CAS 成功但图静默不执行, 项目会停在 WRITING 且无法继续
        if (!"await_manual".equals(graphRunner.pendingNodeId(id))) {
            return Result.fail("当前不在章节人工复审中断点, 请刷新后重试; 若项目已失败, 请使用「从失败处继续写」");
        }
        // 状态仍应是 WRITING (图中断), 但兼容 FAILED (旧流程遗留)
        boolean ok = projectService.casStatus(id,
                Set.of(NovelStatus.WRITING.name(), NovelStatus.FAILED.name()),
                NovelStatus.WRITING, "人工放行, 继续写作");
        if (!ok) {
            return Result.fail("当前状态不支持人工干预, 请刷新后重试");
        }
        String content = req == null ? null : req.editedContent;
        String hint = req == null ? null : req.customPrompt;
        graphRunner.approveAndContinue(id, content, hint);
        return Result.success();
    }

    /** 硬门禁触发后的人工干预: 重写当前章 (从 chapter_writer 重跑) */
    @PostMapping("/project/{id}/manual-rerun")
    public Result<Void> manualRerun(@PathVariable Long id,
                                     @RequestBody(required = false) ManualRerunRequest req) {
        NovelProject project = projectService.getProject(id);
        if (project == null) {
            return Result.fail("作品不存在");
        }
        if (!"await_manual".equals(graphRunner.pendingNodeId(id))) {
            return Result.fail("当前不在章节人工复审中断点, 请刷新后重试; 若项目已失败, 请使用「从失败处继续写」");
        }
        boolean ok = projectService.casStatus(id,
                Set.of(NovelStatus.WRITING.name(), NovelStatus.FAILED.name()),
                NovelStatus.WRITING, "人工触发本章重写");
        if (!ok) {
            return Result.fail("当前状态不支持人工干预, 请刷新后重试");
        }
        String hint = req == null ? null : req.customPrompt;
        boolean fullRewrite = req != null && req.isFullRewrite();
        graphRunner.rerunChapter(id, hint, fullRewrite);
        return Result.success();
    }

    /** 保存人工编辑后的章节正文 (人工放行前可选步骤) */
    @PutMapping("/chapter/{chapterId}/draft")
    public Result<Void> saveEditedDraft(@PathVariable Long chapterId, @RequestBody EditedDraftRequest req) {
        NovelChapter chapter = projectService.getChapter(chapterId);
        if (chapter == null) {
            return Result.fail("章节不存在");
        }
        if (req.content == null || req.content.isBlank()) {
            return Result.fail("正文不能为空");
        }
        projectService.savePolished(chapter.getProjectId(), chapter.getChapterNo(), req.content);
        // 同步到图状态, 否则后续人工放行时润色仍拿检查点里的旧草稿覆盖人工修改
        graphRunner.updateDraftState(chapter.getProjectId(), chapter.getChapterNo(), req.content);
        return Result.success();
    }

    /** 章节详情: 正文 + 审核结论 */
    @GetMapping("/chapter/{chapterId}")
    public Result<NovelChapter> chapter(@PathVariable Long chapterId) {
        NovelChapter chapter = projectService.getChapter(chapterId);
        return chapter == null ? Result.fail("章节不存在") : Result.success(chapter);
    }

    /** 删除作品 (连带章节与协同日志) */
    @DeleteMapping("/project/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        if (projectService.getProject(id) == null) {
            return Result.fail("作品不存在");
        }
        projectService.deleteProject(id);
        return Result.success();
    }

    private int normalizeTotalChapters(Integer total) {
        if (total == null || total <= 0) {
            return DEFAULT_TOTAL_CHAPTERS;
        }
        return Math.min(total, MAX_TOTAL_CHAPTERS);
    }

    // ==================== 请求 / 响应对象 ====================

    @Data
    public static class CreateRequest {
        /** 作品名 */
        private String title;
        /** 题材 code, 见 GET /novel/genres */
        private String genre;
        /** 用户录入的想法或初始大纲 */
        private String userIdea;
        /** 目标章节数 */
        private Integer totalChapters;
    }

    @Data
    public static class OutlineRequest {
        private String outline;
    }

    @Data
    public static class WorldRerunRequest {
        /** 人工给设定师的修改意见, 重生成时注入 prompt */
        private String hint;
    }

    @Data
    public static class VolumeRerunRequest {
        /** 人工给分卷大纲师的修改意见, 重生成时注入 prompt */
        private String hint;
    }

    @Data
    public static class WorldUpdateRequest {
        /** 人工修改后的世界观/规则体系 (Markdown) */
        private String worldview;
        /** 人工修改后的主要人物设定 (Markdown), 可为空 */
        private String characters;
    }

    @Data
    public static class ManualApproveRequest {
        /** 可选: 人工编辑后的章节正文, 若提供则先写入 DB 再放行 */
        private String editedContent;
        /** 可选: 给写手/润色编辑的额外提示词, 对放行后的流程生效 */
        private String customPrompt;
    }

    @Data
    public static class ManualRerunRequest {
        /** 可选: 给写手的额外提示词, 重写时会注入 prompt */
        private String customPrompt;
        /** 可选: true = 完全重写, 不参考上一稿 (仍须遵循大纲/设定/前情) */
        private boolean fullRewrite;
    }

    @Data
    public static class EditedDraftRequest {
        private String content;
    }

    @Data
    public static class ProjectDetailVO {
        @JsonSerialize(using = ToStringSerializer.class)
        private Long id;
        private String title;
        private String genre;
        private String genreLabel;
        private String userId;
        private String userIdea;
        private String worldview;
        private String characters;
        private String outline;
        private String writingConsole;
        private String status;
        private String stage;
        private Integer totalChapters;
        private Integer currentChapter;
        private String errorMsg;
        private LocalDateTime createTime;
        private List<ChapterBriefVO> chapters;
    }

    @Data
    public static class ChapterBriefVO {
        @JsonSerialize(using = ToStringSerializer.class)
        private Long id;
        private Integer chapterNo;
        private String title;
        private String summary;
        private Boolean reviewPassed;
        private Boolean needsReview;
        private Integer rewriteRounds;
        private Integer wordCount;
        /** 审核问题摘要 (截断到 80 字以内, 供章节列表行直接展示) */
        private String reviewSummary;

        static ChapterBriefVO of(NovelChapter chapter) {
            ChapterBriefVO vo = new ChapterBriefVO();
            vo.setId(chapter.getId());
            vo.setChapterNo(chapter.getChapterNo());
            vo.setTitle(chapter.getTitle());
            vo.setSummary(chapter.getSummary());
            vo.setReviewPassed(chapter.getReviewPassed());
            vo.setNeedsReview(chapter.getNeedsReview());
            vo.setRewriteRounds(chapter.getRewriteRounds());
            vo.setWordCount(chapter.getWordCount());
            vo.setReviewSummary(extractReviewSummary(chapter.getReviewIssues()));
            return vo;
        }

        /**
         * 从审核 JSON 里挑出最严重的 2 条问题做摘要。
         * 如果有 high severity 就优先用, 否则用 medium。
         */
        static String extractReviewSummary(String reviewIssuesJson) {
            if (reviewIssuesJson == null || reviewIssuesJson.isBlank()) return null;
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = om.readTree(reviewIssuesJson);
                com.fasterxml.jackson.databind.JsonNode issues = root.get("issues");
                if (issues == null || !issues.isArray() || issues.isEmpty()) return null;
                java.util.List<String> picked = new java.util.ArrayList<>();
                // 先抓 high
                for (com.fasterxml.jackson.databind.JsonNode issue : issues) {
                    String sev = issue.path("severity").asText("").toLowerCase();
                    if ("high".equals(sev)) {
                        picked.add(issue.path("description").asText(""));
                        if (picked.size() >= 2) break;
                    }
                }
                // high 不够再用 medium
                if (picked.size() < 2) {
                    for (com.fasterxml.jackson.databind.JsonNode issue : issues) {
                        String sev = issue.path("severity").asText("").toLowerCase();
                        if ("medium".equals(sev)) {
                            String desc = issue.path("description").asText("");
                            if (!picked.contains(desc)) picked.add(desc);
                            if (picked.size() >= 2) break;
                        }
                    }
                }
                if (picked.isEmpty()) return null;
                String joined = String.join(" / ", picked);
                // 截断到 80 字, 末尾加 ...
                return joined.length() > 80 ? joined.substring(0, 77) + "..." : joined;
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    @Data
    public static class StatusVO {
        private String status;
        private String stage;
        private Integer totalChapters;
        private Integer currentChapter;
        private String errorMsg;
        private List<AgentLogVO> logs;
    }

    @Data
    public static class AgentLogVO {
        @JsonSerialize(using = ToStringSerializer.class)
        private Long id;
        private Integer chapterNo;
        private String agentName;
        private String stage;
        private Integer elapsedMs;
        private String outputText;
        private LocalDateTime createTime;

        static AgentLogVO of(NovelAgentLog row) {
            AgentLogVO vo = new AgentLogVO();
            vo.setId(row.getId());
            vo.setChapterNo(row.getChapterNo());
            vo.setAgentName(row.getAgentName());
            vo.setStage(row.getStage());
            vo.setElapsedMs(row.getElapsedMs());
            vo.setOutputText(row.getOutputText());
            vo.setCreateTime(row.getCreateTime());
            return vo;
        }
    }
}
