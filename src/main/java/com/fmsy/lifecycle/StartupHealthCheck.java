package com.fmsy.lifecycle;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.config.FtpPoolConfig;
import com.fmsy.ftp.FtpPool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动健康检查。
 *
 * <p>验证关键外部依赖连通性:
 * <ul>
 *   <li>DB 不可达 → 致命,退出进程(轮询服务依赖 DB 读命令)</li>
 *   <li>FTP 不可达 → 仅告警,不阻塞启动。
 *       每个 FTP 独立检查,一个不通不影响其他 FTP 的正常传输。
 *       FTP 池每次借连接都会尝试新建,网络恢复后下一轮 poll 即可正常工作,
 *       无需重启。</li>
 * </ul>
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class StartupHealthCheck {

    private final DataSourceConfig.DbPool dbPool;
    private final FtpPool ftpPool;
    private final FtpPoolConfig ftpPoolConfig;

    @EventListener(ApplicationReadyEvent.class)
    public void healthCheck() {
        int ftpCount = ftpPoolConfig.getConfigs() != null ? ftpPoolConfig.getConfigs().size() : 0;
        log.info("Startup health check: {} DB(s), {} FTP config(s)", dbPool.listIds().size(), ftpCount);

        List<String> dbFailures = new ArrayList<>();
        checkDatabases(dbFailures);
        if (!dbFailures.isEmpty()) {
            log.error("Startup health check FAILED — DB unreachable ({} issue(s)):", dbFailures.size());
            for (String f : dbFailures) {
                log.error("  - {}", f);
            }
            log.error("Exiting: polling service requires DB connectivity");
            new Thread(() -> System.exit(1), "fmsy-startup-exit").start();
            return;
        }

        int ftpFailed = checkFtpServers();
        if (ftpFailed > 0) {
            log.warn("Startup health check: {} FTP config(s) unreachable (will retry per-poll, "
                    + "no restart needed). All DB(s) OK.", ftpFailed);
        } else {
            log.info("Startup health check PASSED: all DB(s) and FTP(s) reachable");
        }
    }

    /** 验证每个数据源。不可达的追加到 failures 列表 */
    private void checkDatabases(List<String> failures) {
        for (String id : dbPool.listIds()) {
            DataSource ds = dbPool.getDataSource(id);
            if (ds == null) {
                failures.add("DB " + id + ": DataSource is null");
                continue;
            }
            try (Connection conn = ds.getConnection()) {
                boolean valid = conn.isValid(5);
                if (!valid) {
                    failures.add("DB " + id + ": connection isValid returned false");
                } else {
                    log.info("DB {} OK", id);
                }
            } catch (Exception e) {
                failures.add("DB " + id + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        }
    }

    /**
     * 验证每个 FTP 配置。不可达的仅 WARN,不影响启动。
     * @return 不可达的 FTP 数量
     */
    private int checkFtpServers() {
        if (ftpPoolConfig.getConfigs() == null) return 0;
        int failed = 0;
        for (FtpPoolConfig.FtpConfig cfg : ftpPoolConfig.getConfigs()) {
            String id = cfg.getId();
            try {
                boolean ok = ftpPool.ping(id);
                if (!ok) {
                    log.warn("FTP {} ({}) unreachable at startup — will retry on first use",
                            id, cfg.getHost());
                    failed++;
                } else {
                    log.info("FTP {} OK ({})", id, cfg.getHost());
                }
            } catch (Exception e) {
                log.warn("FTP {} ({}) ping failed at startup: {} — will retry on first use",
                        id, cfg.getHost(), e.getMessage());
                failed++;
            }
        }
        return failed;
    }
}
