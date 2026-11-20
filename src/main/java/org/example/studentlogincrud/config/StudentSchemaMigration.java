package org.example.studentlogincrud.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;

@Component
public class StudentSchemaMigration implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StudentSchemaMigration.class);
    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;

    public StudentSchemaMigration(JdbcTemplate jdbcTemplate, Environment environment) {
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureColumn("student", "oj_password", "CHAR(6) NULL COMMENT 'OJ login password, six alphanumeric characters'");
        ensureCandidateStudentIdCapacity();
        ensureColumn("candidate_profile", "oj_account", "VARCHAR(64) NULL COMMENT 'OJ account'");
        ensureColumn("candidate_profile", "oj_password", "CHAR(6) NULL COMMENT 'OJ password, six alphanumeric characters'");
        ensureColumn("candidate_profile", "created_at", "DATETIME NULL COMMENT 'application submission time'");
        ensureColumn("candidate_profile", "had_experience", "CHAR(1) NULL COMMENT 'whether applicant had prior experience'");
        ensureColumn("candidate_profile", "apply_reason", "TEXT NULL COMMENT 'application reason'");
        ensureColumn("candidate_profile", "grade", "VARCHAR(32) NULL COMMENT 'applicant grade'");
        ensureColumn("candidate_profile", "experience", "TEXT NULL COMMENT 'programming experience'");
        ensureColumn("oj_reserved_account", "assigned_student_no", "VARCHAR(32) NULL UNIQUE COMMENT 'student number using this account'");
        importCandidateProfilesFromExcelOnce();
        importReservedAccountsOnce();
        reconcileReservedAccountsInUse();
    }

    private void importCandidateProfilesFromExcelOnce() {
        importExternalSeedOnce("candidate_profile.excel.seed.v4", "app.seed.candidate-profile");
    }

    private void importReservedAccountsOnce() {
        importExternalSeedOnce("oj_reserved_account.seed.v1", "app.seed.oj-reserved-account");
    }

    private void importExternalSeedOnce(String seedKey, String locationProperty) {
        Integer imported = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_config WHERE config_key = ?", Integer.class, seedKey);
        if (imported != null && imported > 0) {
            return;
        }

        Resource seed = resolveSeedResource(locationProperty);
        if (seed == null) {
            log.warn("跳过初始化 SQL {}：未找到种子资源", seedKey);
            return;
        }

        Connection connection = DataSourceUtils.getConnection(jdbcTemplate.getDataSource());
        try {
            ScriptUtils.executeSqlScript(connection, seed);
            jdbcTemplate.update("INSERT IGNORE INTO app_config (config_key, config_value) VALUES (?, ?)",
                    seedKey, "completed");
        } finally {
            DataSourceUtils.releaseConnection(connection, jdbcTemplate.getDataSource());
        }
    }

    private Resource resolveSeedResource(String locationProperty) {
        String fileName = locationProperty.equalsIgnoreCase("app.seed.candidate-profile")
                ? "db/candidate_profile_excel_seed.sql"
                : "db/oj_reserved_account_seed.sql";
        ClassPathResource classPathSeed = new ClassPathResource(fileName);
        if (classPathSeed.exists()) {
            return classPathSeed;
        }
        String location = environment.getProperty(locationProperty, "").trim();
        if (location.isEmpty()) {
            return null;
        }
        FileSystemResource fileSeed = new FileSystemResource(location);
        return fileSeed.exists() && fileSeed.isReadable() ? fileSeed : null;
    }

    private void reconcileReservedAccountsInUse() {
        jdbcTemplate.update("UPDATE oj_reserved_account r "
                + "JOIN student s ON s.oj_account = r.oj_account "
                + "SET r.assigned_student_no = s.number "
                + "WHERE r.assigned_student_no IS NULL");
    }

    private void ensureColumn(String tableName, String columnName, String definition) {
        Integer columnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, tableName, columnName
        );
        if (columnCount != null && columnCount == 0) {
            jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
        }
    }

    private void ensureCandidateStudentIdCapacity() {
        Integer columnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'candidate_profile' "
                        + "AND COLUMN_NAME = 'student_id' AND DATA_TYPE = 'varchar' "
                        + "AND CHARACTER_MAXIMUM_LENGTH >= 32",
                Integer.class
        );
        if (columnCount == null || columnCount == 0) {
            jdbcTemplate.execute("ALTER TABLE candidate_profile MODIFY COLUMN student_id "
                    + "VARCHAR(32) NOT NULL COMMENT 'student number as submitted'");
        }
    }
}
