package org.example.studentlogincrud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.example.studentlogincrud.dto.SessionScoreSyncResult;
import org.example.studentlogincrud.dto.SessionSyncStatus;
import org.example.studentlogincrud.dto.StudentScoreSyncResult;
import org.example.studentlogincrud.dto.SyncScoresResponse;
import org.example.studentlogincrud.dto.ApplyScoreChangesRequest;
import org.example.studentlogincrud.entity.Student;
import org.example.studentlogincrud.entity.StudentExam;
import org.example.studentlogincrud.mapper.StudentExamMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 考试成绩存储：student_exam 一行 = 一个学生的一场比赛。
 * 上午/下午各记一次互不覆盖。
 *
 * 爬虫同步规则：首次成绩和无变化照常保存；任何已有成绩变化都先保留原值，
 * 管理员检查清单后可选择性应用清单中的本次爬取值。
 */
@Service
public class ExamScoreService extends ServiceImpl<StudentExamMapper, StudentExam> {

    private static final Logger log = LoggerFactory.getLogger(ExamScoreService.class);

    private final OjCrawlerService ojCrawlerService;

    public ExamScoreService(OjCrawlerService ojCrawlerService) {
        this.ojCrawlerService = ojCrawlerService;
    }

    /** 某学生各场次成绩：sessionName('AM'/'PM') → 记录。 */
    public Map<String, StudentExam> scoreMapBySession(Long studentId) {
        Map<String, StudentExam> result = new HashMap<>();
        for (StudentExam exam : list(new LambdaQueryWrapper<StudentExam>()
                .eq(StudentExam::getStudentId, studentId))) {
            result.put(exam.getSessionName(), exam);
        }
        return result;
    }

    /** 全部成绩按学生分组：studentId → sessionName → 记录（管理后台列表用）。 */
    public Map<Long, Map<String, StudentExam>> mapAllByStudent() {
        Map<Long, Map<String, StudentExam>> result = new HashMap<>();
        for (StudentExam exam : list()) {
            result.computeIfAbsent(exam.getStudentId(), key -> new HashMap<>())
                    .put(exam.getSessionName(), exam);
        }
        return result;
    }

    /** 写入或更新某学生某场次的成绩（直接覆盖，供爬虫/手动编辑落库）。 */
    public void recordScore(Long studentId, OjCrawlerService.Session session, BigDecimal score) {
        StudentExam existing = getOne(new LambdaQueryWrapper<StudentExam>()
                .eq(StudentExam::getStudentId, studentId)
                .eq(StudentExam::getSessionName, session.name()));
        LocalDateTime now = LocalDateTime.now();
        Long contestId = ojCrawlerService.contestIdOf(session);
        if (existing == null) {
            StudentExam row = new StudentExam();
            row.setStudentId(studentId);
            row.setSessionName(session.name());
            row.setContestId(contestId);
            row.setScore(score);
            row.setCrawlTime(now);
            save(row);
        } else {
            update(new LambdaUpdateWrapper<StudentExam>()
                    .eq(StudentExam::getId, existing.getId())
                    .set(StudentExam::getScore, score)
                    .set(StudentExam::getContestId, contestId)
                    .set(StudentExam::getCrawlTime, now));
        }
    }

    /** 手动录入/修改某场次成绩（后台表单用）；score 为 null 时删除该场记录（留空 = 清除）。 */
    public void setManualScore(Long studentId, OjCrawlerService.Session session, BigDecimal score) {
        StudentExam existing = getOne(new LambdaQueryWrapper<StudentExam>()
                .eq(StudentExam::getStudentId, studentId)
                .eq(StudentExam::getSessionName, session.name()));
        if (score == null) {
            if (existing != null) {
                removeById(existing.getId());
            }
            return;
        }
        if (existing == null) {
            StudentExam row = new StudentExam();
            row.setStudentId(studentId);
            row.setSessionName(session.name());
            row.setContestId(ojCrawlerService.contestIdOf(session));
            row.setScore(score);
            row.setCrawlTime(LocalDateTime.now());
            save(row);
        } else {
            // 保留原 contest_id（记录成绩来源），只更新分数与时间
            update(new LambdaUpdateWrapper<StudentExam>()
                    .eq(StudentExam::getId, existing.getId())
                    .set(StudentExam::getScore, score)
                    .set(StudentExam::getCrawlTime, LocalDateTime.now()));
        }
    }

    /** 同步全部学生（批量按钮），生成逐人清单。 */
    public SyncScoresResponse syncAll(List<Student> students) {
        SyncScoresResponse response = new SyncScoresResponse();
        response.setStudentCount(students.size());
        response.setAmEnabled(ojCrawlerService.isSessionEnabled(OjCrawlerService.Session.AM));
        response.setPmEnabled(ojCrawlerService.isSessionEnabled(OjCrawlerService.Session.PM));

        List<StudentScoreSyncResult> results = new ArrayList<>();
        Map<Long, StudentScoreSyncResult> resultMap = new HashMap<>();
        for (Student student : students) {
            StudentScoreSyncResult item = new StudentScoreSyncResult();
            item.setStudentId(student.getId());
            item.setName(student.getName());
            item.setStudentNo(student.getStudentNo());
            results.add(item);
            resultMap.put(student.getId(), item);
        }

        if (response.isAmEnabled()) {
            response.setAmMatched(syncSession(OjCrawlerService.Session.AM, students, resultMap));
        }
        if (response.isPmEnabled()) {
            response.setPmMatched(syncSession(OjCrawlerService.Session.PM, students, resultMap));
        }
        response.setStudents(results);
        response.setBlockedChangeCount((int) results.stream().filter(this::hasBlockedChange).count());
        return response;
    }

    private boolean hasBlockedChange(StudentScoreSyncResult item) {
        return item.getAm().getStatus() == SessionSyncStatus.BLOCKED_CHANGE
                || item.getPm().getStatus() == SessionSyncStatus.BLOCKED_CHANGE;
    }

    private int syncSession(OjCrawlerService.Session session, List<Student> students,
                            Map<Long, StudentScoreSyncResult> resultMap) {
        Map<String, BigDecimal> rankScores = ojCrawlerService.fetchRankScores(session, true);
        int matched = 0;
        for (Student student : students) {
            BigDecimal score = null;
            if (student.getOjAccount() != null) {
                score = rankScores.get(student.getOjAccount());
            }
            if (score == null) {
                score = rankScores.get(student.getStudentNo());
            }
            StudentScoreSyncResult item = resultMap.get(student.getId());
            SessionScoreSyncResult sessionResult = session == OjCrawlerService.Session.AM
                    ? item.getAm() : item.getPm();
            sessionResult.setFetchedScore(score);

            if (score == null) {
                sessionResult.setStatus(SessionSyncStatus.NOT_FOUND);
                continue;
            }
            StudentExam existing = getOne(new LambdaQueryWrapper<StudentExam>()
                    .eq(StudentExam::getStudentId, student.getId())
                    .eq(StudentExam::getSessionName, session.name()));
            BigDecimal stored = existing != null ? existing.getScore() : null;
            sessionResult.setStoredScore(stored);

            SessionSyncStatus status = classifyScoreChange(stored, score);
            if (status == SessionSyncStatus.BLOCKED_CHANGE) {
                // 有成绩变化一律先保留原值，人工审核后写入清单中的精确值。
                sessionResult.setStatus(status);
                continue;
            }
            recordScore(student.getId(), session, score);
            sessionResult.setStatus(status);
            matched++;
        }
        log.info("同步{}成绩完成：{}/{} 人保存", sessionLabel(session), matched, students.size());
        return matched;
    }

    /** Apply the exact reviewed value, but reject stale review if the stored baseline changed. */
    @Transactional
    public int applyReviewedChanges(List<ApplyScoreChangesRequest.ScoreChange> changes) {
        int applied = 0;
        if (changes == null) return applied;
        for (ApplyScoreChangesRequest.ScoreChange change : changes) {
            if (change == null || change.getStudentId() == null || change.getScore() == null) continue;
            OjCrawlerService.Session session;
            try { session = OjCrawlerService.Session.valueOf(change.getSession()); }
            catch (Exception invalidSession) { continue; }
            StudentExam current = getOne(new LambdaQueryWrapper<StudentExam>()
                    .eq(StudentExam::getStudentId, change.getStudentId())
                    .eq(StudentExam::getSessionName, session.name())
                    .last("FOR UPDATE"));
            BigDecimal currentScore = current == null ? null : current.getScore();
            if (currentScore == null || change.getExpectedScore() == null
                    || currentScore.compareTo(change.getExpectedScore()) != 0) continue;
            recordScore(change.getStudentId(), session, change.getScore());
            applied++;
        }
        return applied;
    }

    public static SessionSyncStatus classifyScoreChange(BigDecimal stored, BigDecimal fetched) {
        if (fetched == null) return SessionSyncStatus.NOT_FOUND;
        if (stored == null) return SessionSyncStatus.APPLIED;
        return stored.compareTo(fetched) == 0 ? SessionSyncStatus.UNCHANGED : SessionSyncStatus.BLOCKED_CHANGE;
    }

    private String sessionLabel(OjCrawlerService.Session session) {
        return session == OjCrawlerService.Session.AM ? "上午场" : "下午场";
    }
}


