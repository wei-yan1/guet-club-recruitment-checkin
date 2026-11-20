package org.example.studentlogincrud.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 单个学生单场的成绩同步结果。 */
@Data
public class SessionScoreSyncResult {
    private SessionSyncStatus status = SessionSyncStatus.SKIPPED;
    /** 库中原成绩（null = 原本没有） */
    private BigDecimal storedScore;
    /** 本次爬到的成绩（null = 未爬到） */
    private BigDecimal fetchedScore;
}
