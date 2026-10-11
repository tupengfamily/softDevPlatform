package com.learning.springboot.bootstrap;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 沙箱教学库初始化
 * <p>
 * 在应用启动时创建 students / scores / courses 三张表并灌入种子数据,
 * 学生可以放心执行 SELECT/INSERT/UPDATE/DELETE 而不破坏真实数据。
 * <p>
 * 通过 SandboxDataSourceConfig 中的 sandboxJdbcTemplate 注入, 完全独立于主库。
 */
@Component
public class SandboxDataInitializer {

    private static final Logger log = LoggerFactory.getLogger(SandboxDataInitializer.class);

    private final JdbcTemplate sandboxJdbc;

    public SandboxDataInitializer(@Qualifier("sandboxJdbcTemplate") JdbcTemplate sandboxJdbc) {
        this.sandboxJdbc = sandboxJdbc;
    }

    @PostConstruct
    @Transactional
    public void init() {
        try {
            sandboxJdbc.execute("""
                CREATE TABLE IF NOT EXISTS students (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    class_id INTEGER NOT NULL,
                    gender TEXT,
                    enrolled_at TEXT
                )
                """);

            sandboxJdbc.execute("""
                CREATE TABLE IF NOT EXISTS courses (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    teacher TEXT,
                    credit INTEGER DEFAULT 3
                )
                """);

            sandboxJdbc.execute("""
                CREATE TABLE IF NOT EXISTS scores (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    student_id INTEGER NOT NULL,
                    course_id INTEGER NOT NULL,
                    score REAL NOT NULL,
                    exam_date TEXT,
                    FOREIGN KEY (student_id) REFERENCES students(id),
                    FOREIGN KEY (course_id) REFERENCES courses(id)
                )
                """);

            // 幂等: 仅当 students 为空时灌种子
            Integer count = sandboxJdbc.queryForObject("SELECT COUNT(*) FROM students", Integer.class);
            if (count == null || count == 0) {
                seedData();
                log.info("✅ 沙箱教学库初始化完成: students/courses/scores");
            } else {
                log.info("沙箱教学库已存在 {} 行学生数据, 跳过种子", count);
            }
        } catch (Exception e) {
            log.error("沙箱教学库初始化失败", e);
        }
    }

    private void seedData() {
        // 课程
        sandboxJdbc.update("INSERT INTO courses(name, teacher, credit) VALUES (?,?,?)",
            "Java 基础", "张老师", 4);
        sandboxJdbc.update("INSERT INTO courses(name, teacher, credit) VALUES (?,?,?)",
            "数据库原理", "李老师", 3);
        sandboxJdbc.update("INSERT INTO courses(name, teacher, credit) VALUES (?,?,?)",
            "Web 开发", "王老师", 4);

        // 学生
        Object[][] students = {
            {"张三", 1, "M", "2024-09-01"},
            {"李四", 1, "F", "2024-09-01"},
            {"王五", 1, "M", "2024-09-01"},
            {"赵六", 2, "M", "2024-09-01"},
            {"钱七", 2, "F", "2024-09-01"},
            {"孙八", 2, "M", "2024-09-01"},
            {"周九", 3, "F", "2025-09-01"},
            {"吴十", 3, "M", "2025-09-01"}
        };
        for (Object[] s : students) {
            sandboxJdbc.update(
                "INSERT INTO students(name, class_id, gender, enrolled_at) VALUES (?,?,?,?)",
                s);
        }

        // 成绩(每个学生每门课一条)
        for (int sid = 1; sid <= 8; sid++) {
            int classId = sid <= 3 ? 1 : (sid <= 6 ? 2 : 3);
            for (int cid = 1; cid <= 3; cid++) {
                // 用 sid/cid 生成稳定的伪随机分(60-99)
                double score = 60 + ((sid * 7 + cid * 11) % 40);
                String examDate = "2025-06-" + String.format("%02d", (sid + cid) % 28 + 1);
                sandboxJdbc.update(
                    "INSERT INTO scores(student_id, course_id, score, exam_date) VALUES (?,?,?,?)",
                    sid, cid, score, examDate);
            }
        }
    }
}