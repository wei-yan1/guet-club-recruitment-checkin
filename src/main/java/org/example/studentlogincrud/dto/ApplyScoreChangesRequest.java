package org.example.studentlogincrud.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ApplyScoreChangesRequest {
    private List<ScoreChange> changes;

    @Data
    public static class ScoreChange {
        private Long studentId;
        private String session;
        private BigDecimal expectedScore;
        private BigDecimal score;
    }
}
