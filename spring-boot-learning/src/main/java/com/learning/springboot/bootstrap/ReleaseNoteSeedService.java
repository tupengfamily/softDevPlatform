package com.learning.springboot.bootstrap;

import com.learning.springboot.entity.ReleaseNote;
import com.learning.springboot.repository.ReleaseNoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 版本更新公告种子数据
 * <p>
 * 幂等 upsert: 按版本号匹配,不存在则插入、存在则同步标题/内容(保证种子文案与代码一致)。
 * 内容为富文本 HTML(前端 Quill 编辑器同格式)。
 * 之后每次发布新版本,由管理员在"更新日志"页发布新公告(或在此追加)。
 */
@Service
public class ReleaseNoteSeedService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseNoteSeedService.class);

    @Autowired private ReleaseNoteRepository releaseNoteRepository;

    @Transactional
    public void seedReleaseNotes() {
        seed("1.0.0", "首个版本 · 后台管理系统上线",
            LocalDate.of(2026, 8, 20),
            """
            <ul>
            <li>后台管理基础能力: 用户 / 角色 / 权限 / 数据字典 / 操作日志</li>
            <li>JWT 登录鉴权 + BCrypt 密码加密</li>
            <li>统一响应格式与全局异常处理</li>
            <li>SQLite 持久化, 开箱即用</li>
            </ul>
            """);
        seed("2.0.0", "学习平台 · 阶段式课程与在线沙箱",
            LocalDate.of(2026, 9, 28),
            """
            <ul>
            <li>全新学习中心: 24 章 / 110+ 课时, 5 大学习阶段</li>
            <li>Java 在线沙箱: 单文件编译运行, 5 秒超时, 编译错误定位</li>
            <li>SQL 教学沙箱: 独立教学库, 仅允许 DML, 一键重置</li>
            <li>学习看板: 管理员看全员进度, 学员看个人进度</li>
            </ul>
            """);
        seed("2.1.0", "体验升级 · 字符串专题扩充与工程化完善",
            LocalDate.of(2026, 10, 11),
            """
            <ul>
            <li>字符串章节大幅扩充: 字符与 Unicode、不可变性与常量池、Text Blocks、常用 API 与 StringBuilder</li>
            <li>课时正文支持代码块与行内代码渲染</li>
            <li>新增更新日志与版本公告, 支持意见反馈与系统评分</li>
            <li>支持 Docker 一键部署</li>
            </ul>
            """);
        log.info("✅ 版本更新公告种子数据完成");
    }

    private void seed(String version, String title, LocalDate date, String content) {
        ReleaseNote n = releaseNoteRepository.findByVersion(version).orElseGet(ReleaseNote::new);
        n.setVersion(version);
        n.setTitle(title);
        n.setReleaseDate(date);
        n.setContent(content.strip());
        releaseNoteRepository.save(n);
    }
}
