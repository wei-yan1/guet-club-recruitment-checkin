package org.example.studentlogincrud;

import org.junit.jupiter.api.Test;
import org.example.studentlogincrud.service.impl.StudentServiceImpl;
import org.example.studentlogincrud.service.ExamScoreService;
import org.example.studentlogincrud.dto.SessionSyncStatus;
import java.math.BigDecimal;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudentLoginCrudApplicationTests {
    @Test
    void studentNumberMustBeTenDigitsStartingWith25Or26() throws Exception {
        Method method = StudentServiceImpl.class.getDeclaredMethod("isValidStudentNo", String.class);
        method.setAccessible(true);
        StudentServiceImpl service = new StudentServiceImpl(null, null, null);

        assertTrue((Boolean) method.invoke(service, "2500123456"));
        assertTrue((Boolean) method.invoke(service, "2600123456"));
        assertFalse((Boolean) method.invoke(service, "2400123456"));
        assertFalse((Boolean) method.invoke(service, "2700123456"));
        assertFalse((Boolean) method.invoke(service, "260012345"));
        assertFalse((Boolean) method.invoke(service, "26001234567"));
        assertFalse((Boolean) method.invoke(service, "abcdefghij"));
    }
    @Test
    void scoreSyncSavesFirstAndUnchangedScoresButFlagsAnyExistingScoreChange() {
        assertEquals(SessionSyncStatus.APPLIED,
                ExamScoreService.classifyScoreChange(null, new BigDecimal("70")));
        assertEquals(SessionSyncStatus.UNCHANGED,
                ExamScoreService.classifyScoreChange(new BigDecimal("70.00"), new BigDecimal("70")));
        assertEquals(SessionSyncStatus.BLOCKED_CHANGE,
                ExamScoreService.classifyScoreChange(new BigDecimal("70"), new BigDecimal("85")));
        assertEquals(SessionSyncStatus.BLOCKED_CHANGE,
                ExamScoreService.classifyScoreChange(new BigDecimal("70"), new BigDecimal("55")));
        assertEquals(SessionSyncStatus.NOT_FOUND,
                ExamScoreService.classifyScoreChange(new BigDecimal("70"), null));
    }

}
