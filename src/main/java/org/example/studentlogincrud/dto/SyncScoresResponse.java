package org.example.studentlogincrud.dto;

import lombok.Data;

import java.util.List;

/** 手动同步成绩的结果统计 + 逐人清单。 */
@Data
public class SyncScoresResponse {
    /** 参与同步的学生总数 */
    private int studentCount;
    /** 是否配置了上午场比赛 */
    private boolean amEnabled;
    /** 是否配置了下午场比赛 */
    private boolean pmEnabled;
    /** 上午场保存成绩的人数（含未变化） */
    private int amMatched;
    /** 下午场保存成绩的人数（含未变化） */
    private int pmMatched;
    /** 已有成绩发生变化、被拦截的人数（等待人工检查） */
    private int blockedChangeCount;
    /** 每个学生的同步结果清单 */
    private List<StudentScoreSyncResult> students;
}

