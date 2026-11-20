package org.example.studentlogincrud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.example.studentlogincrud.entity.Result;
import org.example.studentlogincrud.entity.CandidateProfile;
import org.example.studentlogincrud.entity.Student;
import org.example.studentlogincrud.entity.StudentExam;
import org.example.studentlogincrud.mapper.CandidateProfileMapper;
import org.example.studentlogincrud.mapper.StudentMapper;
import org.example.studentlogincrud.service.ExamScoreService;
import org.example.studentlogincrud.service.OjCrawlerService;
import org.example.studentlogincrud.service.StudentService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class StudentServiceImpl extends ServiceImpl<StudentMapper, Student> implements StudentService {
    /** 学号：10 位数字，25/26 级 */
    private static final String STUDENT_NUMBER_REGEX = "^(25|26)\\d{8}$";
    /** OJ 账号：字母数字及 _ - . 组合，1~64 位 */
    private static final String OJ_ACCOUNT_REGEX = "^[A-Za-z0-9_.-]{1,64}$";
    /** OJ 密码：恰好 6 位字母数字 */
    private static final String OJ_PASSWORD_REGEX = "^[A-Za-z0-9]{6}$";
    private static final String PUBLIC_ID_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ExamScoreService examScoreService;
    private final CandidateProfileMapper candidateProfileMapper;
    private final JdbcTemplate jdbcTemplate;

    public StudentServiceImpl(ExamScoreService examScoreService, CandidateProfileMapper candidateProfileMapper,
                              JdbcTemplate jdbcTemplate) {
        this.examScoreService = examScoreService;
        this.candidateProfileMapper = candidateProfileMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Result<Object> check(String studentNo, String ojAccount) {
        if (studentNo != null && !studentNo.trim().isEmpty() && !isValidStudentNo(studentNo)) {
            return Result.error(400, "Student number must be 10 digits and start with 25 or 26");
        }
        if (ojAccount != null && !ojAccount.trim().isEmpty()) {
            String account = ojAccount.trim();
            if (!account.matches(OJ_ACCOUNT_REGEX)) {
                return Result.error(400, "OJ account contains invalid characters");
            }
            if (count(new LambdaQueryWrapper<Student>().eq(Student::getOjAccount, account)) > 0) {
                return Result.error(400, "OJ account already bound to another record");
            }
        }
        return Result.success(true);
    }

    @Override
    @Transactional
    public Result<Student> create(Student student) {
        Result<Object> validation = validateStudent(student, false);
        if (validation.getCode() != 200) {
            return Result.error(validation.getCode(), validation.getMessage());
        }
        String studentNo = student.getStudentNo() == null ? null : student.getStudentNo().trim();
        if (!isValidStudentNo(studentNo)) {
            return Result.error(400, "Student number must be 10 digits and start with 25 or 26");
        }
        if (!Boolean.FALSE.equals(student.getAutoMatch())) {
            CandidateProfile candidate = candidateProfileMapper.selectById(studentNo);
            if (candidate != null) {
                if (student.getName() == null || student.getName().isBlank()) {
                    student.setName(candidate.getName());
                }
                if (hasCredentials(candidate.getOjAccount(), candidate.getOjPassword())
                        && !isOjAccountTaken(candidate.getOjAccount(), null)) {
                    student.setOjAccount(candidate.getOjAccount());
                    student.setOjPassword(candidate.getOjPassword());
                }
            }
        }
        boolean useReservedAccount = Boolean.FALSE.equals(student.getAutoMatch())
                || !hasCredentials(student.getOjAccount(), student.getOjPassword());
        ReservedAccount reserved = useReservedAccount ? lockNextReservedAccount() : null;
        if (useReservedAccount && reserved == null) {
            return Result.error(409, "No reserved OJ accounts remain");
        }
        if (reserved != null) {
            student.setOjAccount(reserved.account());
            student.setOjPassword(reserved.password());
        }
        student.setStudentNo(studentNo);
        validation = validateStudent(student, true);
        if (validation.getCode() != 200) {
            return Result.error(validation.getCode(), validation.getMessage());
        }
        if (count(new LambdaQueryWrapper<Student>()
                .eq(Student::getStudentNo, studentNo)) > 0) {
            return Result.error(400, "Student number already exists");
        }
        student.setName(student.getName().trim());
        student.setOjPassword(student.getOjPassword().trim());
        student.setRegisterTime(LocalDateTime.now());
        student.setPublicId(createPublicId());
        save(student);
        if (reserved != null) {
            jdbcTemplate.update("UPDATE oj_reserved_account SET assigned_student_no = ? WHERE oj_account = ? AND assigned_student_no IS NULL",
                    studentNo, reserved.account());
        }
        applySessionScores(student.getId(), student);
        return Result.success(student);
    }

    @Override
    @Transactional
    public Result<Student> update(Long id, Student student) {
        Student existing = getById(id);
        if (existing == null) {
            return Result.error(404, "Student not found");
        }
        Result<Object> validation = validateStudent(student, false);
        if (validation.getCode() != 200) {
            return Result.error(validation.getCode(), validation.getMessage());
        }
        // 用显式 set 更新，确保学号匹配到的 OJ 信息一起替换。
        LambdaUpdateWrapper<Student> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Student::getId, id);
        if (student.getStudentNo() != null && !student.getStudentNo().trim().equals(existing.getStudentNo())) {
            String studentNo = student.getStudentNo().trim();
            CandidateProfile candidate = candidateProfileMapper.selectById(studentNo);
            boolean numberTaken = count(new LambdaQueryWrapper<Student>()
                    .eq(Student::getStudentNo, studentNo)
                    .ne(Student::getId, id)) > 0;
            if (numberTaken) {
                return Result.error(400, "Student number already exists");
            }
            wrapper.set(Student::getStudentNo, studentNo);
            if (candidate != null && candidate.getName() != null && !candidate.getName().isBlank()) {
                wrapper.set(Student::getName, candidate.getName());
            } else if (student.getName() != null) {
                wrapper.set(Student::getName, student.getName().trim());
            }
            boolean oldWasReserved = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM oj_reserved_account WHERE oj_account = ? AND assigned_student_no = ?",
                    Integer.class, existing.getOjAccount(), existing.getStudentNo()) > 0;
            if (candidate != null && hasCredentials(candidate.getOjAccount(), candidate.getOjPassword())
                    && !isOjAccountTaken(candidate.getOjAccount(), id)) {
                wrapper.set(Student::getOjAccount, candidate.getOjAccount());
                wrapper.set(Student::getOjPassword, candidate.getOjPassword());
                if (oldWasReserved) {
                    jdbcTemplate.update("UPDATE oj_reserved_account SET assigned_student_no = NULL WHERE oj_account = ?",
                            existing.getOjAccount());
                }
            } else if (oldWasReserved) {
                wrapper.set(Student::getOjAccount, existing.getOjAccount());
                wrapper.set(Student::getOjPassword, existing.getOjPassword());
                jdbcTemplate.update("UPDATE oj_reserved_account SET assigned_student_no = ? WHERE oj_account = ?",
                        studentNo, existing.getOjAccount());
            } else {
                ReservedAccount reserved = lockNextReservedAccount();
                if (reserved == null) {
                    return Result.error(409, "No reserved OJ accounts remain");
                }
                wrapper.set(Student::getOjAccount, reserved.account());
                wrapper.set(Student::getOjPassword, reserved.password());
                jdbcTemplate.update("UPDATE oj_reserved_account SET assigned_student_no = ? WHERE oj_account = ? AND assigned_student_no IS NULL",
                        studentNo, reserved.account());
            }
        }
        if (student.getStudentNo() == null && student.getName() != null) {
            wrapper.set(Student::getName, student.getName().trim());
        } else if (student.getStudentNo() != null
                && student.getStudentNo().trim().equals(existing.getStudentNo())
                && student.getName() != null) {
            wrapper.set(Student::getName, student.getName().trim());
        }
        if (student.getScore() != null) {
            wrapper.set(Student::getScore, student.getScore());
        }
        if (student.getStudentNo() == null && student.getOjPassword() != null) {
            wrapper.set(Student::getOjPassword, student.getOjPassword().trim());
        }
        // OJ 账号和密码以候选名单为准。
        update(wrapper);
        applySessionScores(id, student);
        return Result.success(getById(id));
    }

    @Override
    public Result<Student> select(Long id) {
        Student student = getById(id);
        return student == null ? Result.error(404, "Student not found") : Result.success(student);
    }

    @Override
    @Transactional
    public Result<Object> delete(Long id) {
        Student existing = getById(id);
        if (existing == null) {
            return Result.error(404, "Student not found");
        }
        // 级联清理该学生的两场成绩记录，避免残留孤儿数据
        examScoreService.remove(new LambdaQueryWrapper<StudentExam>()
                .eq(StudentExam::getStudentId, id));
        jdbcTemplate.update("UPDATE oj_reserved_account SET assigned_student_no = NULL WHERE assigned_student_no = ?",
                existing.getStudentNo());
        removeById(id);
        return Result.success();
    }

    @Override
    public Student getByPublicId(String publicId) {
        if (publicId == null || publicId.trim().isEmpty()) {
            return null;
        }
        return getOne(new LambdaQueryWrapper<Student>().eq(Student::getPublicId, publicId.trim()));
    }

    @Override
    public CandidateProfile match(String studentNo) {
        if (!isValidStudentNo(studentNo)) {
            return null;
        }
        return candidateProfileMapper.selectById(studentNo.trim());
    }

    /** 列表附带 student_exam 中上午/下午两场爬取成绩，供后台展示。 */
    @Override
    public List<Student> list() {
        List<Student> students = super.list();
        Map<Long, Map<String, StudentExam>> examMap = examScoreService.mapAllByStudent();
        for (Student student : students) {
            Map<String, StudentExam> sessions = examMap.get(student.getId());
            if (sessions == null) {
                continue;
            }
            StudentExam am = sessions.get(OjCrawlerService.Session.AM.name());
            StudentExam pm = sessions.get(OjCrawlerService.Session.PM.name());
            student.setScoreAm(am != null ? am.getScore() : null);
            student.setScorePm(pm != null ? pm.getScore() : null);
        }
        return students;
    }

    private Result<Object> validateStudent(Student student, boolean requireAll) {
        if (student == null) {
            return Result.error(400, "Student information is required");
        }
        if ((requireAll || student.getStudentNo() != null) && !isValidStudentNo(student.getStudentNo())) {
            return Result.error(400, "Student number must be 10 digits and start with 25 or 26");
        }
        if (requireAll || student.getName() != null) {
            if (student.getName() == null || student.getName().trim().isEmpty()) {
                return Result.error(400, "Student name is required");
            }
        }
        if (student.getOjAccount() != null) {
            String account = normalizeAccount(student.getOjAccount());
            if (account != null && !account.matches(OJ_ACCOUNT_REGEX)) {
                return Result.error(400, "OJ account contains invalid characters");
            }
            if (requireAll && account == null) {
                return Result.error(400, "OJ account is required");
            }
        } else if (requireAll) {
            return Result.error(400, "OJ account is required");
        }
        if (requireAll || student.getOjPassword() != null) {
            if (student.getOjPassword() == null || !student.getOjPassword().matches(OJ_PASSWORD_REGEX)) {
                return Result.error(400, "OJ password must be exactly 6 letters or digits");
            }
        }
        if (student.getScore() != null && student.getScore().signum() < 0) {
            return Result.error(400, "Score cannot be negative");
        }
        if (student.getScoreAm() != null && student.getScoreAm().signum() < 0) {
            return Result.error(400, "Morning score cannot be negative");
        }
        if (student.getScorePm() != null && student.getScorePm().signum() < 0) {
            return Result.error(400, "Afternoon score cannot be negative");
        }
        return Result.success();
    }

    /** 空白账号归一化为 null（未绑定），其余去空格。 */
    private String normalizeAccount(String ojAccount) {
        if (ojAccount == null) {
            return null;
        }
        String trimmed = ojAccount.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 保存表单里手动填写的上午/下午成绩到 student_exam；null = 清除该场成绩。 */
    private void applySessionScores(Long studentId, Student source) {
        examScoreService.setManualScore(studentId, OjCrawlerService.Session.AM, source.getScoreAm());
        examScoreService.setManualScore(studentId, OjCrawlerService.Session.PM, source.getScorePm());
    }

    private boolean isValidStudentNo(String studentNo) {
        return studentNo != null && studentNo.trim().matches(STUDENT_NUMBER_REGEX);
    }

    private String createPublicId() {
        StringBuilder publicId = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            publicId.append(PUBLIC_ID_CHARS.charAt(RANDOM.nextInt(PUBLIC_ID_CHARS.length())));
        }
        return publicId.toString();
    }

    private boolean hasCredentials(String account, String password) {
        return account != null && account.matches(OJ_ACCOUNT_REGEX)
                && password != null && password.matches(OJ_PASSWORD_REGEX);
    }

    private boolean isOjAccountTaken(String account, Long exceptStudentId) {
        LambdaQueryWrapper<Student> query = new LambdaQueryWrapper<Student>().eq(Student::getOjAccount, account);
        if (exceptStudentId != null) {
            query.ne(Student::getId, exceptStudentId);
        }
        return count(query) > 0;
    }

    private ReservedAccount lockNextReservedAccount() {
        List<ReservedAccount> available = jdbcTemplate.query(
                "SELECT oj_account, oj_password FROM oj_reserved_account "
                        + "WHERE assigned_student_no IS NULL "
                        + "AND NOT EXISTS (SELECT 1 FROM student s WHERE s.oj_account = oj_reserved_account.oj_account) "
                        + "ORDER BY oj_account LIMIT 1 FOR UPDATE",
                (rs, rowNum) -> new ReservedAccount(rs.getString("oj_account"), rs.getString("oj_password")));
        return available.isEmpty() ? null : available.get(0);
    }

    private record ReservedAccount(String account, String password) {}
}

