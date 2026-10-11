package com.learning.springboot.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 启动初始化器
 * <p>
 * 仅作为 CommandLineRunner 入口,实际逻辑委托给 DataSeedService 中带 @Transactional 的方法
 * (绕过 Spring AOP 自调用导致 @Transactional 失效的问题)。
 */
@Component
public class AdminBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    @Autowired private DataSeedService seedService;
    @Autowired private LearningDataSeedService learningDataSeedService;
    @Autowired private ReleaseNoteSeedService releaseNoteSeedService;

    @Override
    public void run(String... args) {
        seedService.seedPermissions();
        seedService.seedRoles();
        seedService.seedDictTypes();
        seedService.seedDefaultUsers();
        learningDataSeedService.seedLearningData();
        releaseNoteSeedService.seedReleaseNotes();
        log.info("✅ 管理系统初始化完成! 默认账号: admin/admin123, user/user123");
    }
}