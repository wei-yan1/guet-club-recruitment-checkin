package org.example.studentlogincrud.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("candidate_profile")
public class CandidateProfile {
    @TableId("student_id")
    private String studentNo;

    private String name;

    private String qq;

    @TableField("oj_account")
    private String ojAccount;

    @TableField("oj_password")
    private String ojPassword;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("had_experience")
    private String hadExperience;

    @TableField("apply_reason")
    private String applyReason;

    private String grade;

    private String experience;

    private String email;
}
