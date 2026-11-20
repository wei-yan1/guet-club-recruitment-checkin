package org.example.studentlogincrud.dto;

import lombok.Data;

/** 单个学生的成绩同步结果（批量同步清单 / 单人同步共用）。 */
@Data
public class StudentScoreSyncResult {
    private Long studentId;
    private String name;
    private String studentNo;
    private SessionScoreSyncResult am = new SessionScoreSyncResult();
    private SessionScoreSyncResult pm = new SessionScoreSyncResult();
}
