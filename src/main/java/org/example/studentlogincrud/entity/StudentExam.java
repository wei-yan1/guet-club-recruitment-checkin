package org.example.studentlogincrud.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 考试成绩：每个学生每场考试一行（AM=上午场 / PM=下午场），
 * 两个场次各记一次、互不覆盖；爬虫同步成功即写入。
 */
@Data
@TableName("student_exam")
public class StudentExam {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("student_id")
    private Long studentId;

    /** 场次：AM / PM */
    private String sessionName;

    /** 成绩来源的 OJ 比赛 id */
    @TableField("contest_id")
    private Long contestId;

    private BigDecimal score;

    /** 最近一次爬取时间 */
    @TableField("crawl_time")
    private LocalDateTime crawlTime;
}
