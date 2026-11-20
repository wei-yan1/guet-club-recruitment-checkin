package org.example.studentlogincrud.dto;

/** 单个学生单场的同步结果状态。 */
public enum SessionSyncStatus {
    /** 场次未配置/未参与 */
    SKIPPED,
    /** 未爬到该学生的成绩 */
    NOT_FOUND,
    /** 成绩没变，按原样确认 */
    UNCHANGED,
    /** 原来没有成绩，首次保存成功 */
    APPLIED,
    /** 原成绩与爬取成绩不同，默认保留原分等待人工检查 */
    BLOCKED_CHANGE
}
