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
 * 在应用启动时创建 classes / students / courses / scores 四张表并灌入场景化种子数据:
 * <ul>
 *   <li>4 个班级、12 名学生、5 门课程、35 条成绩</li>
 *   <li>故意留白的场景: 陈三/褚四没有成绩记录(LEFT JOIN 练习)、操作系统课无人选修(反 JOIN 练习)</li>
 *   <li>分数带 45~99 的分布, 含不及格样本(UPDATE 演练)</li>
 * </ul>
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
            // 旧库迁移: 缺 classes 表说明是早期三表结构 -> 整库重建换新种子
            if (!tableExists("classes")) {
                sandboxJdbc.execute("DROP TABLE IF EXISTS scores");
                sandboxJdbc.execute("DROP TABLE IF EXISTS students");
                sandboxJdbc.execute("DROP TABLE IF EXISTS courses");
                log.info("检测到旧版教学库结构, 已重建为 classes/students/courses/scores 四表");
            }

            sandboxJdbc.execute("""
                CREATE TABLE IF NOT EXISTS classes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    head_teacher TEXT
                )
                """);

            sandboxJdbc.execute("""
                CREATE TABLE IF NOT EXISTS students (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    class_id INTEGER NOT NULL,
                    gender TEXT,
                    enrolled_at TEXT,
                    FOREIGN KEY (class_id) REFERENCES classes(id)
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
                log.info("✅ 沙箱教学库初始化完成: classes/students/courses/scores");
            } else {
                log.info("沙箱教学库已存在 {} 行学生数据, 跳过种子", count);
            }
        } catch (Exception e) {
            log.error("沙箱教学库初始化失败", e);
        }
    }

    private boolean tableExists(String name) {
        Integer n = sandboxJdbc.queryForObject(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", Integer.class, name);
        return n != null && n > 0;
    }

    private void seedData() {
        // 班级(为 JOIN 场景提供第三张关联表)
        Object[][] classes = {
            {1, "一班", "张老师"}, {2, "二班", "李老师"},
            {3, "三班", "王老师"}, {4, "四班", "赵老师"}
        };
        for (Object[] c : classes) {
            sandboxJdbc.update("INSERT INTO classes(id, name, head_teacher) VALUES (?,?,?)", c);
        }

        // 课程: 第 4 门"操作系统"故意没有任何成绩记录(LEFT JOIN 找"没人选的课")
        Object[][] courses = {
            {1, "Java 基础", "张老师", 4},
            {2, "数据库原理", "李老师", 3},
            {3, "Web 开发", "王老师", 4},
            {4, "操作系统", "赵老师", 3},
            {5, "算法与数据结构", "钱老师", 4}
        };
        for (Object[] c : courses) {
            sandboxJdbc.update("INSERT INTO courses(id, name, teacher, credit) VALUES (?,?,?,?)", c);
        }

        // 学生: 陈三(11)/褚四(12) 故意没有成绩记录(LEFT JOIN 找"没有成绩的学生")
        Object[][] students = {
            {1, "张三", 1, "M", "2024-09-01"},
            {2, "李四", 1, "F", "2024-09-01"},
            {3, "王五", 1, "M", "2024-09-01"},
            {4, "赵六", 2, "M", "2024-09-01"},
            {5, "钱七", 2, "F", "2024-09-01"},
            {6, "孙八", 2, "M", "2024-09-01"},
            {7, "周九", 3, "F", "2025-09-01"},
            {8, "吴十", 3, "M", "2025-09-01"},
            {9, "郑一", 4, "F", "2025-09-01"},
            {10, "冯二", 4, "M", "2025-09-01"},
            {11, "陈三", 3, "M", "2025-09-01"},
            {12, "褚四", 1, "F", "2025-09-01"}
        };
        for (Object[] s : students) {
            sandboxJdbc.update(
                "INSERT INTO students(id, name, class_id, gender, enrolled_at) VALUES (?,?,?,?,?)", s);
        }

        // 成绩: 学生 1-10 × 课程 1-3, 另加学生 1-5 × 课程 5(算法)
        // 分数 45~99 稳定伪随机分布, 含不及格样本(供 UPDATE 加分演练)
        for (int sid = 1; sid <= 10; sid++) {
            for (int cid = 1; cid <= 3; cid++) {
                double score = 45 + ((sid * 7 + cid * 11) % 55);
                String examDate = "2025-06-" + String.format("%02d", (sid + cid) % 28 + 1);
                sandboxJdbc.update(
                    "INSERT INTO scores(student_id, course_id, score, exam_date) VALUES (?,?,?,?)",
                    sid, cid, score, examDate);
            }
        }
        for (int sid = 1; sid <= 5; sid++) {
            double score = 60 + ((sid * 13) % 40);
            sandboxJdbc.update(
                "INSERT INTO scores(student_id, course_id, score, exam_date) VALUES (?,?,?,?)",
                sid, 5, score, "2025-07-0" + sid);
        }
    }
}
