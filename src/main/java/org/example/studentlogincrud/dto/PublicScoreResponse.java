package org.example.studentlogincrud.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PublicScoreResponse {
    private String name;
    private String studentNo;
    /** 绑定的 OJ 比赛账号（未绑定时为 null） */
    private String ojAccount;
    /** OJ 登录密码（扫码查询页展示） */
    private String ojPassword;
    /** 总分：上午、下午场取最高分；两场都没有时回退手动成绩 */
    private BigDecimal score;
    /** 上午场成绩（null = 未同步/未考） */
    private BigDecimal scoreAm;
    /** 下午场成绩（null = 未同步/未考） */
    private BigDecimal scorePm;
}
