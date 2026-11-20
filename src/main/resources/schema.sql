-- Administrator table. Administrators log in with name and password.
CREATE TABLE IF NOT EXISTS admin (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(100) NOT NULL UNIQUE COMMENT 'administrator name/login name',
    password VARCHAR(255) NOT NULL COMMENT 'password or BCrypt hash'
);

-- Student table. Students do not have login credentials.
CREATE TABLE IF NOT EXISTS student (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    public_id CHAR(16) NOT NULL UNIQUE COMMENT '16 random characters',
    number CHAR(10) NOT NULL UNIQUE COMMENT '10 digits starting with 25 or 26',
    oj_account VARCHAR(64) NULL UNIQUE COMMENT 'OJ contest account, nullable',
    oj_password CHAR(6) NULL COMMENT 'OJ login password, six alphanumeric characters',
    name VARCHAR(100) NOT NULL COMMENT 'student name',
    score DECIMAL(5,2) NULL COMMENT 'manual score fallback (used when no crawled scores exist)',
    register_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'registration time'
);

-- Candidate directory imported from the recruitment roster; kept separate from registered students.
CREATE TABLE IF NOT EXISTS candidate_profile (
    student_id VARCHAR(32) NOT NULL PRIMARY KEY COMMENT 'student number as submitted',
    name VARCHAR(100) NOT NULL COMMENT 'student name',
    qq VARCHAR(20) NULL COMMENT 'QQ number',
    email VARCHAR(254) NULL COMMENT 'email address',
    oj_account VARCHAR(64) NULL COMMENT 'OJ account',
    oj_password CHAR(6) NULL COMMENT 'OJ password, six alphanumeric characters',
    created_at DATETIME NULL COMMENT 'application submission time',
    had_experience CHAR(1) NULL COMMENT 'whether applicant had prior experience',
    apply_reason TEXT NULL COMMENT 'application reason',
    grade VARCHAR(32) NULL COMMENT 'applicant grade',
    experience TEXT NULL COMMENT 'programming experience'
);

-- Spare OJ accounts are kept separately; they are not tied to fabricated student numbers.
CREATE TABLE IF NOT EXISTS oj_reserved_account (
    oj_account VARCHAR(64) NOT NULL PRIMARY KEY COMMENT 'reserved OJ username',
    oj_password CHAR(6) NOT NULL COMMENT 'reserved OJ password, six alphanumeric characters',
    reserved_name VARCHAR(100) NOT NULL COMMENT 'placeholder label',
    assigned_student_no VARCHAR(32) NULL UNIQUE COMMENT 'student number using this account'
);

-- Crawled exam scores: one row per student per session (AM = morning, PM = afternoon),
-- so a student can be recorded once (either session) or twice (both sessions).
CREATE TABLE IF NOT EXISTS student_exam (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    student_id BIGINT NOT NULL COMMENT 'references student.id',
    session_name VARCHAR(10) NOT NULL COMMENT 'AM = morning session, PM = afternoon session',
    contest_id BIGINT NULL COMMENT 'OJ contest id the score was crawled from',
    score DECIMAL(5,2) NULL COMMENT 'crawled total score of that contest',
    crawl_time DATETIME NULL COMMENT 'last crawl time',
    UNIQUE KEY uk_student_session (student_id, session_name)
);

-- Runtime-editable configuration (edited from the admin UI; application.properties only seeds defaults).
CREATE TABLE IF NOT EXISTS app_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_key VARCHAR(100) NOT NULL UNIQUE COMMENT 'e.g. oj.base-url, oj.contest-id-am, oj.contest-id-pm',
    config_value TEXT NULL
);

-- For an existing database created before oj_account / student_exam / app_config existed, run:
-- ALTER TABLE student ADD COLUMN oj_account VARCHAR(64) NULL UNIQUE COMMENT 'OJ contest account, nullable';
-- ALTER TABLE candidate_profile ADD COLUMN oj_account VARCHAR(64) NULL COMMENT 'OJ account';
-- ALTER TABLE candidate_profile ADD COLUMN oj_password CHAR(6) NULL COMMENT 'OJ password, six alphanumeric characters';
-- plus the CREATE TABLE student_exam / app_config statements above (safe to re-run thanks to IF NOT EXISTS).
