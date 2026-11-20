package org.example.studentlogincrud.controller;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.servlet.http.HttpServletRequest;
import org.example.studentlogincrud.dto.PublicScoreResponse;
import org.example.studentlogincrud.entity.Result;
import org.example.studentlogincrud.entity.Student;
import org.example.studentlogincrud.entity.StudentExam;
import org.example.studentlogincrud.service.ExamScoreService;
import org.example.studentlogincrud.service.OjCrawlerService;
import org.example.studentlogincrud.service.StudentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/public/scores")
public class PublicScoreController {
    private final StudentService studentService;
    private final ExamScoreService examScoreService;
    private final OjCrawlerService ojCrawlerService;
    private final String publicBaseUrl;

    public PublicScoreController(
            StudentService studentService,
            ExamScoreService examScoreService,
            OjCrawlerService ojCrawlerService,
            @Value("${app.public-base-url:}") String publicBaseUrl
    ) {
        this.studentService = studentService;
        this.examScoreService = examScoreService;
        this.ojCrawlerService = ojCrawlerService;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim();
    }

    @GetMapping("/{publicId}")
    public Result<PublicScoreResponse> query(@PathVariable String publicId) {
        Student student = studentService.getByPublicId(publicId);
        if (student == null) {
            return Result.error(404, "Student score not found");
        }
        PublicScoreResponse response = new PublicScoreResponse();
        response.setName(student.getName());
        response.setStudentNo(student.getStudentNo());
        response.setOjAccount(student.getOjAccount());
        response.setOjPassword(student.getOjPassword());

        Map<String, StudentExam> exams = examScoreService.scoreMapBySession(student.getId());
        StudentExam am = exams.get(OjCrawlerService.Session.AM.name());
        StudentExam pm = exams.get(OjCrawlerService.Session.PM.name());

        // 爬虫启用时实时刷新：先查排名缓存（60 秒），爬到新成绩即落库；失败回退库中成绩
        if (ojCrawlerService.isEnabled()) {
            am = refreshSession(student, OjCrawlerService.Session.AM, am);
            pm = refreshSession(student, OjCrawlerService.Session.PM, pm);
        }

        BigDecimal scoreAm = am != null ? am.getScore() : null;
        BigDecimal scorePm = pm != null ? pm.getScore() : null;
        response.setScoreAm(scoreAm);
        response.setScorePm(scorePm);
        response.setScore(totalScore(scoreAm, scorePm, student.getScore()));
        return Result.success(response);
    }

    /** 实时爬某一场：按 OJ 账号匹配（回退学号），爬到就落库并返回最新值；失败返回库中原值。 */
    private StudentExam refreshSession(Student student, OjCrawlerService.Session session, StudentExam stored) {
        if (!ojCrawlerService.isSessionEnabled(session)) {
            return stored;
        }
        try {
            String account = student.getOjAccount() != null ? student.getOjAccount() : student.getStudentNo();
            BigDecimal live = ojCrawlerService.fetchScore(session, account);
            if (live == null && student.getOjAccount() != null) {
                live = ojCrawlerService.fetchScore(session, student.getStudentNo());
            }
            if (live != null) {
                examScoreService.recordScore(student.getId(), session, live);
                StudentExam row = new StudentExam();
                row.setStudentId(student.getId());
                row.setSessionName(session.name());
                row.setScore(live);
                return row;
            }
        } catch (Exception e) {
            // 爬取失败不阻断查分，回退数据库成绩
        }
        return stored;
    }

    /** 总分取上午、下午场中的最高分；两场都没有时回退手动填写的成绩。 */
    private BigDecimal totalScore(BigDecimal scoreAm, BigDecimal scorePm, BigDecimal manual) {
        if (scoreAm == null && scorePm == null) {
            return manual;
        }
        if (scoreAm == null) {
            return scorePm;
        }
        if (scorePm == null) {
            return scoreAm;
        }
        return scoreAm.max(scorePm);
    }

    @GetMapping(value = "/{publicId}/qrcode", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qrcode(@PathVariable String publicId, HttpServletRequest request) {
        Student student = studentService.getByPublicId(publicId);
        if (student == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            String queryUrl = buildPublicScoreUrl(publicId, request);
            BitMatrix matrix = new QRCodeWriter().encode(
                    queryUrl,
                    BarcodeFormat.QR_CODE,
                    360,
                    360,
                    Map.of(EncodeHintType.MARGIN, 1)
            );
            MatrixToImageWriter.writeToStream(matrix, "PNG", output);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .cacheControl(CacheControl.noStore())
                    .body(output.toByteArray());
        } catch (WriterException | IOException exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private String buildPublicScoreUrl(String publicId, HttpServletRequest request) {
        String baseUrl = publicBaseUrl.isBlank()
                ? request.getScheme() + "://" + request.getServerName()
                + (request.getServerPort() == 80 || request.getServerPort() == 443 ? "" : ":" + request.getServerPort())
                : publicBaseUrl.replaceAll("/$", "");
        return baseUrl + "/score.html?publicId=" + publicId;
    }
}
