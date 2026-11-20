package org.example.studentlogincrud.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("student")
public class Student {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("public_id")
    private String publicId;

    @TableField("number")
    private String studentNo;

    /** OJ 比赛账号（来自 candidate_profile 的学号匹配） */
    @TableField("oj_account")
    private String ojAccount;

    /** OJ 登录密码（6 位字母数字） */
    @TableField("oj_password")
    private String ojPassword;

    /** 新增表单是否要求从候选名单自动匹配（不映射数据库列） */
    @TableField(exist = false)
    private Boolean autoMatch;

    private String name;

    private BigDecimal score;

    /** 上午场成绩（来自 student_exam，仅供列表展示，不映射表列） */
    @TableField(exist = false)
    private BigDecimal scoreAm;

    /** 下午场成绩（来自 student_exam，仅供列表展示，不映射表列） */
    @TableField(exist = false)
    private BigDecimal scorePm;

    @TableField("register_time")
    private LocalDateTime registerTime;
}
