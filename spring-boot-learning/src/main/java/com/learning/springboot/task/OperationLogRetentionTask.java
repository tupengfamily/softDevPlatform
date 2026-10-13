package com.learning.springboot.task;

import com.learning.springboot.service.OperationLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;

/**
 * 操作日志保留期清理任务
 * <p>
 * 操作日志默认只保留 7 天(可通过 app.log-retention-days 配置调整):
 * <ul>
 *   <li>应用启动时立即清理一次超期日志(重启即生效)</li>
 *   <li>此后每天凌晨 03:00 定时清理一次</li>
 * </ul>
 * retention-days &lt;= 0 表示关闭自动清理(永久保留)。
 */
@Component
public class OperationLogRetentionTask {

    private static final Logger log = LoggerFactory.getLogger(OperationLogRetentionTask.class);

    @Autowired private OperationLogService operationLogService;

    /** 日志保留天数,默认 7 天; <=0 表示不自动清理 */
    @Value("${app.log-retention-days:7}")
    private int retentionDays;

    /** 启动时先清一次,保证存量超期日志立即删除 */
    @PostConstruct
    public void cleanOnStartup() {
        clean();
    }

    /** 每天凌晨 03:00 定时清理 */
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanDaily() {
        clean();
    }

    private void clean() {
        if (retentionDays <= 0) return;
        try {
            LocalDateTime before = LocalDateTime.now().minusDays(retentionDays);
            int n = operationLogService.cleanBefore(before);
            if (n > 0) {
                log.info("操作日志保留 {} 天,已清理 {} 条超期日志(before={})", retentionDays, n, before);
            }
        } catch (Exception e) {
            log.warn("操作日志超期清理失败", e);
        }
    }
}
