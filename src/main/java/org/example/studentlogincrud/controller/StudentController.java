package org.example.studentlogincrud.controller;

import org.example.studentlogincrud.dto.SyncScoresResponse;
import org.example.studentlogincrud.dto.ApplyScoreChangesRequest;
import org.example.studentlogincrud.entity.CandidateProfile;
import org.example.studentlogincrud.entity.Result;
import org.example.studentlogincrud.entity.Student;
import org.example.studentlogincrud.service.ExamScoreService;
import org.example.studentlogincrud.service.StudentService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/students")
public class StudentController {
    private final StudentService studentService;
    private final ExamScoreService examScoreService;

    public StudentController(StudentService studentService, ExamScoreService examScoreService) {
        this.studentService = studentService;
        this.examScoreService = examScoreService;
    }

    @GetMapping
    public Result<List<Student>> list() {
        return Result.success(studentService.list());
    }

    /** 批量同步两场成绩，变化值暂不覆盖，返回待审核清单。 */
    @PostMapping("/sync-scores")
    public Result<SyncScoresResponse> syncScores() {
        return Result.success(examScoreService.syncAll(studentService.list()));
    }

    /** 单个学生同步（编辑弹窗中的按钮）。 */
    @PostMapping("/{id}/sync-score")
    public Result<SyncScoresResponse> syncStudentScore(@PathVariable Long id) {
        Student student = studentService.getById(id);
        if (student == null) return Result.error(404, "Student not found");
        return Result.success(examScoreService.syncAll(List.of(student)));
    }

    /** 只应用管理员从同步清单中勾选的精确变化值。 */
    @PostMapping("/sync-scores/apply")
    public Result<Integer> applyReviewedScoreChanges(@RequestBody ApplyScoreChangesRequest request) {
        if (request == null) return Result.error(400, "Score changes are required");
        return Result.success(examScoreService.applyReviewedChanges(request.getChanges()));
    }

    @PostMapping
    public Result<Student> create(@RequestBody Student student) {
        return studentService.create(student);
    }

    @PutMapping("/{id}")
    public Result<Student> update(@PathVariable Long id, @RequestBody Student student) {
        return studentService.update(id, student);
    }

    @GetMapping("/check")
    public Result<Object> check(@RequestParam(required = false) String studentNo,
                                @RequestParam(required = false) String ojAccount) {
        return studentService.check(studentNo, ojAccount);
    }

    @GetMapping("/match")
    public Result<CandidateProfile> match(@RequestParam String studentNo) {
        CandidateProfile profile = studentService.match(studentNo);
        return profile == null
                ? Result.error(404, "No candidate profile found for this student number")
                : Result.success(profile);
    }

    @GetMapping("/{id}")
    public Result<Student> select(@PathVariable Long id) {
        return studentService.select(id);
    }

    @DeleteMapping("/{id}")
    public Result<Object> delete(@PathVariable Long id) {
        return studentService.delete(id);
    }
}

