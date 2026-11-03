package com.novel.agent.novel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.novel.agent.entity.NovelAgentLog;
import com.novel.agent.entity.NovelChapter;
import com.novel.agent.entity.NovelProject;
import com.novel.agent.mapper.NovelAgentLogMapper;
import com.novel.agent.mapper.NovelChapterMapper;
import com.novel.agent.mapper.NovelProjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 小说项目持久化服务 (PostgreSQL)
 * <p>
 * 所有写操作使用短事务, 不与 LLM 调用混在同一事务里, 避免长事务占用连接。
 * LLM 调用失败时的降级: 仅记录日志, 不抛异常影响主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelProjectService {

    private final NovelProjectMapper projectMapper;
    private final NovelChapterMapper chapterMapper;
    private final NovelAgentLogMapper agentLogMapper;

    // ==================== 项目 ====================

    @Transactional
    public NovelProject create(String title, NovelGenre genre, String userId, String userIdea, int totalChapters) {
        NovelProject project = new NovelProject();
        project.setTitle(title);
        project.setGenre(genre.name());
        project.setUserId(userId == null ? "" : userId);
        project.setUserIdea(userIdea == null ? "" : userIdea);
        project.setTotalChapters(totalChapters);
        project.setCurrentChapter(0);
        project.setStatus(NovelStatus.DRAFT.name());
        project.setStage("已创建");
        projectMapper.insert(project);
        return project;
    }

    public NovelProject getProject(Long id) {
        return projectMapper.selectById(id);
    }

    public List<NovelProject> pageProjects(long page, long size, String userId) {
        LambdaQueryWrapper<NovelProject> wrapper = new LambdaQueryWrapper<NovelProject>()
                .orderByDesc(NovelProject::getCreateTime);
        if (userId != null && !userId.isBlank()) {
            wrapper.eq(NovelProject::getUserId, userId);
        }
        return projectMapper.selectPage(new Page<>(page, size), wrapper).getRecords();
    }

    /**
     * 原子状态流转 (CAS): 仅当当前状态在 expectStatuses 内才更新
     * 返回 false 表示已有任务在跑 (影响行数为 0), 用于防止同一作品被重复触发
     */
    @Transactional
    public boolean casStatus(Long id, Collection<String> expectStatuses, NovelStatus targetStatus, String stage) {
        int updated = projectMapper.update(null, new LambdaUpdateWrapper<NovelProject>()
                .eq(NovelProject::getId, id)
                .in(NovelProject::getStatus, expectStatuses)
                .set(NovelProject::getStatus, targetStatus.name())
                .set(NovelProject::getStage, stage)
                // 新一轮执行开始时清空上一轮的失败原因, 避免前端持续展示过期错误
                .set(NovelProject::getErrorMsg, null)
                .set(NovelProject::getUpdateTime, LocalDateTime.now()));
        return updated > 0;
    }

    public void updateStage(Long id, String stage) {
        try {
            projectMapper.update(null, new LambdaUpdateWrapper<NovelProject>()
                    .eq(NovelProject::getId, id)
                    .set(NovelProject::getStage, stage)
                    .set(NovelProject::getUpdateTime, LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("[novel] 更新进度阶段失败 projectId={} stage={}", id, stage, e);
        }
    }

    public void saveWorldview(Long id, String worldview, String characters) {
        projectMapper.update(null, new LambdaUpdateWrapper<NovelProject>()
                .eq(NovelProject::getId, id)
                .set(NovelProject::getWorldview, worldview)
                .set(NovelProject::getCharacters, characters)
                .set(NovelProject::getUpdateTime, LocalDateTime.now()));
    }

    public void saveOutline(Long id, String outline) {
        projectMapper.update(null, new LambdaUpdateWrapper<NovelProject>()
                .eq(NovelProject::getId, id)
                .set(NovelProject::getOutline, outline)
                .set(NovelProject::getUpdateTime, LocalDateTime.now()));
    }

    public void saveWritingConsole(Long id, String writingConsole) {
        projectMapper.update(null, new LambdaUpdateWrapper<NovelProject>()
                .eq(NovelProject::getId, id)
                .set(NovelProject::getWritingConsole, writingConsole)
                .set(NovelProject::getUpdateTime, LocalDateTime.now()));
    }

    public void saveRollingSummary(Long id, String rollingSummary) {
        projectMapper.update(null, new LambdaUpdateWrapper<NovelProject>()
                .eq(NovelProject::getId, id)
                .set(NovelProject::getRollingSummary, rollingSummary)
                .set(NovelProject::getUpdateTime, LocalDateTime.now()));
    }

    public void updateCurrentChapter(Long id, int chapterNo) {
        projectMapper.update(null, new LambdaUpdateWrapper<NovelProject>()
                .eq(NovelProject::getId, id)
                .set(NovelProject::getCurrentChapter, chapterNo)
                .set(NovelProject::getUpdateTime, LocalDateTime.now()));
    }

    public void markFailed(Long id, String errorMsg) {
        try {
            projectMapper.update(null, new LambdaUpdateWrapper<NovelProject>()
                    .eq(NovelProject::getId, id)
                    .set(NovelProject::getStatus, NovelStatus.FAILED.name())
                    .set(NovelProject::getErrorMsg, truncate(errorMsg, 2000))
                    .set(NovelProject::getUpdateTime, LocalDateTime.now()));
        } catch (Exception e) {
            log.error("[novel] 标记失败状态出错 projectId={}", id, e);
        }
    }

    public void markCompleted(Long id) {
        projectMapper.update(null, new LambdaUpdateWrapper<NovelProject>()
                .eq(NovelProject::getId, id)
                .set(NovelProject::getStatus, NovelStatus.COMPLETED.name())
                .set(NovelProject::getStage, "全部章节写作完成")
                .set(NovelProject::getErrorMsg, null)
                .set(NovelProject::getUpdateTime, LocalDateTime.now()));
    }

    @Transactional
    public void deleteProject(Long id) {
        chapterMapper.delete(new LambdaQueryWrapper<NovelChapter>().eq(NovelChapter::getProjectId, id));
        agentLogMapper.delete(new LambdaQueryWrapper<NovelAgentLog>().eq(NovelAgentLog::getProjectId, id));
        projectMapper.deleteById(id);
    }

    // ==================== 章节 ====================

    public NovelChapter getChapter(Long chapterId) {
        return chapterMapper.selectById(chapterId);
    }

    public List<NovelChapter> listChapters(Long projectId) {
        return chapterMapper.selectList(new LambdaQueryWrapper<NovelChapter>()
                .eq(NovelChapter::getProjectId, projectId)
                .orderByAsc(NovelChapter::getChapterNo));
    }

    /** 续写起点: 已有章节最大编号 + 1 (不信任前端传参) */
    public int nextChapterNo(Long projectId) {
        NovelChapter last = chapterMapper.selectOne(new LambdaQueryWrapper<NovelChapter>()
                .eq(NovelChapter::getProjectId, projectId)
                .orderByDesc(NovelChapter::getChapterNo)
                .last("LIMIT 1"));
        return last == null ? 1 : last.getChapterNo() + 1;
    }

    /** 写手产出正文落库 (按 project_id + chapter_no 幂等 upsert, 支持重写覆盖) */
    @Transactional
    public void saveDraft(Long projectId, int chapterNo, String title, String outline, String content) {
        NovelChapter chapter = findOrNew(projectId, chapterNo);
        chapter.setTitle(title);
        chapter.setOutline(outline);
        chapter.setContent(content);
        chapter.setWordCount(countWords(content));
        saveChapter(chapter);
    }

    /** 润色后覆盖正文 */
    @Transactional
    public void savePolished(Long projectId, int chapterNo, String content) {
        NovelChapter chapter = findOrNew(projectId, chapterNo);
        chapter.setContent(content);
        chapter.setWordCount(countWords(content));
        saveChapter(chapter);
    }

    /** 审核结论落库 */
    @Transactional
    public void saveReview(Long projectId, int chapterNo, boolean passed, String issuesJson,
                           boolean needsReview, int rewriteRounds) {
        NovelChapter chapter = findOrNew(projectId, chapterNo);
        chapter.setReviewPassed(passed);
        chapter.setReviewIssues(issuesJson);
        chapter.setNeedsReview(needsReview);
        chapter.setRewriteRounds(rewriteRounds);
        saveChapter(chapter);
    }

    /** 人工干预时覆盖审核结果: 放行 (passed=true) / 重置 (passed=null, 重新判定) */
    @Transactional
    public void markChapterReviewed(Long projectId, int chapterNo, Boolean passed) {
        NovelChapter chapter = findOrNew(projectId, chapterNo);
        chapter.setReviewPassed(passed);
        chapter.setNeedsReview(false);
        if (passed == null) {
            // 人工触发重写 → 清掉审核结论, 让新一轮审核重新判定
            chapter.setReviewIssues(null);
            chapter.setRewriteRounds(0);
        }
        saveChapter(chapter);
    }

    @Transactional
    public void saveSummary(Long projectId, int chapterNo, String summary) {
        NovelChapter chapter = findOrNew(projectId, chapterNo);
        chapter.setSummary(summary);
        saveChapter(chapter);
    }

    /** 按 (projectId, chapterNo) 取已有章节, 不存在则新建并回填这两个 NOT NULL 字段 */
    private NovelChapter findOrNew(Long projectId, int chapterNo) {
        NovelChapter existing = chapterMapper.selectOne(new LambdaQueryWrapper<NovelChapter>()
                .eq(NovelChapter::getProjectId, projectId)
                .eq(NovelChapter::getChapterNo, chapterNo));
        if (existing != null) {
            return existing;
        }
        NovelChapter created = new NovelChapter();
        created.setProjectId(projectId);
        created.setChapterNo(chapterNo);
        created.setRewriteRounds(0);
        return created;
    }

    private void saveChapter(NovelChapter chapter) {
        chapter.setUpdateTime(LocalDateTime.now());
        if (chapter.getId() == null) {
            chapterMapper.insert(chapter);
        } else {
            chapterMapper.updateById(chapter);
        }
    }

    // ==================== 智能体协同日志 ====================

    /** 记录一次智能体执行 (失败不影响主流程) */
    public void appendAgentLog(Long projectId, Integer chapterNo, AgentRole role,
                               String stage, String inputSummary, String outputText, long elapsedMs) {
        try {
            NovelAgentLog logRow = new NovelAgentLog();
            logRow.setProjectId(projectId);
            logRow.setChapterNo(chapterNo);
            logRow.setAgentName(role.getNodeName());
            logRow.setStage(stage);
            logRow.setInputSummary(truncate(inputSummary, 500));
            logRow.setOutputText(truncate(outputText, 1000));
            logRow.setElapsedMs((int) elapsedMs);
            agentLogMapper.insert(logRow);
        } catch (Exception e) {
            log.warn("[novel] 写入智能体日志失败 projectId={} agent={}", projectId, role.getNodeName(), e);
        }
    }

    /** 最近若干条协同日志 (时间正序返回, 便于前端按顺序展示) */
    public List<NovelAgentLog> recentLogs(Long projectId, int limit) {
        List<NovelAgentLog> rows = agentLogMapper.selectList(new LambdaQueryWrapper<NovelAgentLog>()
                .eq(NovelAgentLog::getProjectId, projectId)
                .orderByDesc(NovelAgentLog::getId)
                .last("LIMIT " + Math.max(1, limit)));
        List<NovelAgentLog> ordered = new ArrayList<>(rows);
        ordered.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        return ordered;
    }

    // ==================== 工具 ====================

    /** 字数统计: 去除空白字符后的长度 */
    public static int countWords(String content) {
        return content == null ? 0 : content.replaceAll("\\s", "").length();
    }

    /** 安全截断, 防止超长文本破坏日志表与响应体 */
    public static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
