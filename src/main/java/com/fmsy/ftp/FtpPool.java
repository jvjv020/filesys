package com.fmsy.ftp;

import com.fmsy.config.FtpPoolConfig;
import com.fmsy.exception.TransferException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * FTP连接池管理器
 *
 * <p>功能:多FTP服务器隔离连接池,空闲超时淘汰,健康检查,故障转移。
 *
 * <p>连接池设计:
 * <ul>
 *   <li>每个 FTP 服务器独立 FtpConfigHolder,持有独立的 {@link ReentrantLock}</li>
 *   <li>idle:空闲连接(带时间戳),busy:正在使用(仅计数)</li>
 *   <li>借连接优先复用 idle 有效连接,池满则等待</li>
 *   <li>归还后 NOOP 验证,无效直接销毁</li>
 * </ul>
 *
 * <p>即借即用:优先使用 {@link #withClient(String, FtpCallback)} 替代手写 getClient-try-finally。
 */
@Slf4j
@Component
public class FtpPool {

    private final Map<String, FtpConfigHolder> pools = new ConcurrentHashMap<>();
    private final ScheduledExecutorService healthCheckScheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "fmsy-ftp-healthcheck");
        t.setDaemon(true);
        return t;
    });

    public FtpPool(FtpPoolConfig config) {
        if (config.getConfigs() != null) {
            for (FtpPoolConfig.FtpConfig ftpConfig : config.getConfigs()) {
                pools.put(ftpConfig.getId(), new FtpConfigHolder(ftpConfig));
                if (ftpConfig.getHealthCheck().isEnabled()) {
                    startHealthCheck(ftpConfig);
                }
            }
        }
    }

    // ==================== 公开 API ====================

    /**
     * 即借即用的回调模板 — 借 → 用 → 还 一行封装。
     *
     * <p>发生 IOException 时自动重试一次(换新连接),调用方无感。
     */
    public <T> T withClient(String ftpId, FtpCallback<T> action) {
        FtpConfigHolder holder = getHolder(ftpId);
        return holder.execute(action);
    }

    /** void 变体 */
    public void withClient(String ftpId, FtpVoidCallback action) {
        FtpConfigHolder holder = getHolder(ftpId);
        holder.execute(action);
    }

    /**
     * 获取原始 FtpClient(仅在 withClient 不够用时使用,如需要早返/分阶段控制流时)。
     */
    public FtpClient getClient(String ftpId) {
        return getHolder(ftpId).getClient();
    }

    public boolean ping(String ftpId) {
        try {
            return withClient(ftpId, client -> {
                try {
                    return client.getClient().sendNoOp();
                } catch (IOException e) {
                    log.warn("FTP ping failed for {}: {}", ftpId, e.getMessage());
                    return false;
                }
            });
        } catch (Exception e) {
            log.error("FTP ping error for {}: {}", ftpId, e.getMessage());
            return false;
        }
    }

    @PreDestroy
    public void close() {
        healthCheckScheduler.shutdownNow();
        for (FtpConfigHolder holder : pools.values()) {
            holder.close();
        }
    }

    // ==================== 内部 ====================

    private FtpConfigHolder getHolder(String ftpId) {
        FtpConfigHolder holder = pools.get(ftpId);
        if (holder == null) {
            throw new IllegalArgumentException("FTP config not found: " + ftpId);
        }
        return holder;
    }

    private void startHealthCheck(FtpPoolConfig.FtpConfig config) {
        FtpConfigHolder holder = pools.get(config.getId());
        int interval = config.getHealthCheck().getIntervalSeconds();
        healthCheckScheduler.scheduleAtFixedRate(holder::checkAllClients, interval, interval, TimeUnit.SECONDS);
    }

    // ==================== 回调接口 ====================

    @FunctionalInterface
    public interface FtpCallback<T> { T run(FtpClient client) throws Exception; }
    @FunctionalInterface
    public interface FtpVoidCallback { void run(FtpClient client) throws Exception; }

    // ==================== 空闲条目 ====================

    private static class IdleEntry {
        final FTPClient client;
        final long idleSince;

        IdleEntry(FTPClient client) {
            this.client = client;
            this.idleSince = System.currentTimeMillis();
        }
    }

    // ==================== 单服务器连接池 ====================

    static class FtpConfigHolder {
        private final FtpPoolConfig.FtpConfig config;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition notFull = lock.newCondition();
        private final List<IdleEntry> idle = new ArrayList<>();
        private final Set<FTPClient> busy = ConcurrentHashMap.newKeySet();

        FtpConfigHolder(FtpPoolConfig.FtpConfig config) {
            this.config = config;
        }

        // ---------- 即借即用:withClient 核心 ----------

        /**
         * 借 → 用 → 还 封装,发生 IOException 时自动重试一次(换新连接)。
         *
         * <p>借出前已在 {@link #getClient()} 中做过 NOOP 验证,
         * 但长时间空闲后服务端可能断开连接,一次重试可覆盖此类场景。
         */
        <T> T execute(FtpCallback<T> action) {
            return executeInternal(action);
        }

        /** void 变体 — 委托给 execute */
        void execute(FtpVoidCallback action) {
            executeInternal(client -> { action.run(client); return null; });
        }

        private <T> T executeInternal(FtpCallback<T> action) {
            FtpClient client = getClient();
            try {
                return action.run(client);
            } catch (IOException e) {
                log.warn("FTP IO failed, retrying with new connection: {}", e.getMessage());
            } catch (Exception e) {
                throw wrap(e, config.getId());
            } finally {
                client.close();
            }
            // 重试一次(换新连接)
            FtpClient retry = getClient();
            try {
                return action.run(retry);
            } catch (Exception e) {
                throw wrap(e, config.getId());
            } finally {
                retry.close();
            }
        }

        private static TransferException wrap(Exception e, String ftpId) {
            if (e instanceof TransferException te) return te;
            return new TransferException("FTP_ACTION_FAILED",
                    "FTP action failed for " + ftpId + ": " + e.getMessage(), e);
        }

        // ---------- 借 / 还 / 删 ----------

        /**
         * 借出一个 FTP 连接。
         *
         * <p>空闲连接复用和池满等待在锁内(快速路径)；
         * 创建新连接({@link #createClientWithFailover})在锁外,
         * 避免网络连接超时期间阻塞其他线程的归还/借出操作。
         *
         * <p>空闲连接的 NOOP 验证在锁外执行,避免网络 I/O 阻塞锁内的
         * 其他线程的借出/归还操作。验证失败的连接会被丢弃并重试。
         */
        FtpClient getClient() {
            int maxIdleSeconds = config.getPool().getMaxIdleTimeSeconds();
            while (true) {
                // Phase 1: 锁内 — 空闲复用 / 等待（不执行网络 I/O）
                FTPClient candidate = borrowIdleOrWait(maxIdleSeconds);
                if (candidate != null) {
                    // Phase 1.5: 锁外 — NOOP 验证（网络 I/O 不在锁内执行）
                    if (!isClientValid(candidate)) {
                        removeClient(candidate);
                        continue; // 验证失败，重试
                    }
                    return new FtpClient(candidate, this);
                }
                // Phase 2: 锁外 — 创建新连接(不阻塞其他线程)
                FTPClient newClient = createClientWithFailover();
                // Phase 3: 锁内 — 注册到 busy; 若池在此期间满则断开重试
                if (tryRegisterNew(newClient)) {
                    return new FtpClient(newClient, this);
                }
                disconnectQuietly(newClient, "overflow");
            }
        }

        /**
         * 锁内:尝试借空闲连接或等待归还。
         * 注意:空闲连接的 NOOP 验证在锁外执行(避免网络 I/O 阻塞其他线程的借出/归还)。
         * @return FTPClient(已加到 busy) 或 null(池有空位,需在锁外创建新连接)
         */
        private FTPClient borrowIdleOrWait(int maxIdleSeconds) {
            lock.lock();
            try {
                while (true) {
                    for (Iterator<IdleEntry> it = idle.iterator(); it.hasNext();) {
                        IdleEntry entry = it.next();
                        long idleMs = System.currentTimeMillis() - entry.idleSince;
                        if (idleMs > maxIdleSeconds * 1000L) {
                            it.remove();
                            disconnectQuietly(entry.client, "idle timeout");
                            continue;
                        }
                        it.remove();
                        // 锁内只做移除，NOOP 验证放锁外执行，避免网络 I/O 阻塞其他线程
                        busy.add(entry.client);
                        return entry.client;
                    }
                    if (idle.size() + busy.size() < config.getPool().getMaxTotal()) {
                        return null; // 锁外创建
                    }
                    try {
                        notFull.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new TransferException("FTP_GET_INTERRUPTED",
                                "FTP getClient interrupted for " + config.getId(), e);
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        /**
         * 锁内:把新连接注册到 busy。
         * @return true=成功;false=池在锁外创建期间被填满
         */
        private boolean tryRegisterNew(FTPClient client) {
            lock.lock();
            try {
                if (idle.size() + busy.size() < config.getPool().getMaxTotal()) {
                    busy.add(client);
                    return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }

        void returnClient(FtpClient ftpClient) {
            FTPClient client = ftpClient.getClient();
            // 锁外验证（网络 I/O 不在锁内执行）
            boolean valid = isClientValid(client);
            lock.lock();
            try {
                busy.remove(client);
                if (valid) {
                    idle.add(new IdleEntry(client));
                } else {
                    disconnectQuietly(client, "invalid return");
                }
                notFull.signal();
            } finally {
                lock.unlock();
            }
        }

        void removeClient(FTPClient client) {
            lock.lock();
            try {
                idle.removeIf(entry -> entry.client == client);
                busy.remove(client);
            } finally {
                lock.unlock();
            }
            // 锁外断开（网络 I/O 不在锁内执行）
            disconnectQuietly(client, "removeClient");
            lock.lock();
            try {
                notFull.signal();
            } finally {
                lock.unlock();
            }
        }

        // ---------- 健康检查 ----------

        void checkAllClients() {
            int maxIdleSeconds = config.getPool().getMaxIdleTimeSeconds();
            List<FTPClient> toDisconnect = new ArrayList<>();
            lock.lock();
            try {
                for (Iterator<IdleEntry> it = idle.iterator(); it.hasNext();) {
                    IdleEntry entry = it.next();
                    long idleMs = System.currentTimeMillis() - entry.idleSince;
                    if (idleMs > maxIdleSeconds * 1000L) {
                        it.remove();
                        toDisconnect.add(entry.client);
                    }
                }
            } finally {
                lock.unlock();
            }
            // 锁外执行断开操作（避免网络 I/O 阻塞其他线程）
            for (FTPClient client : toDisconnect) {
                disconnectQuietly(client, "health check timeout");
            }
        }

        // ---------- 连通性 / 创建 / 销毁 ----------

        private boolean isClientValid(FTPClient client) {
            try {
                return client.isConnected() && client.sendNoOp();
            } catch (IOException e) {
                return false;
            }
        }

        private FTPClient configureClient(FTPClient client, String host) throws IOException {
            client.setConnectTimeout(config.getTimeout());
            client.setDataTimeout(config.getTimeout());
            client.connect(host, config.getPort());
            client.setSoTimeout(config.getTimeout());
            client.login(config.getUsername(), config.getPassword());
            client.enterLocalPassiveMode();
            client.setFileType(FTP.BINARY_FILE_TYPE);
            return client;
        }

        private FTPClient createClientWithFailover() {
            FTPClient client = new FTPClient();
            FtpPoolConfig.Failover failover = config.getFailover();
            List<String> hostsToTry = resolveHostWithFailover();

            if (failover.isEnabled() && hostsToTry.size() > 1) {
                for (int attempt = 0; attempt <= failover.getMaxRetries(); attempt++) {
                    for (int i = 0; i < hostsToTry.size(); i++) {
                        String host = hostsToTry.get(i);
                        try {
                            FTPClient connected = configureClient(client, host);
                            log.info("FTP connected to {} (primary={})", host, i == 0);
                            return connected;
                        } catch (IOException e) {
                            log.error("FTP connection to {} failed: {}", host, e.getMessage());
                            try { client.disconnect(); } catch (IOException ignored) {}
                            client = new FTPClient();
                        }
                    }
                }
                throw new TransferException("FTP_CONNECTION_FAILED",
                        "FTP连接失败，已尝试所有地址: " + hostsToTry);
            }
            try {
                return configureClient(client, config.getHost());
            } catch (IOException e) {
                throw new TransferException("FTP_CONNECTION_FAILED",
                        "FTP连接失败: " + config.getHost(), e);
            }
        }

        private List<String> resolveHostWithFailover() {
            List<String> hosts = new ArrayList<>();
            try {
                InetAddress[] addresses = InetAddress.getAllByName(config.getHost());
                for (InetAddress addr : addresses) {
                    hosts.add(addr.getHostAddress());
                }
            } catch (UnknownHostException e) {
                log.error("Cannot resolve hostname {}, using as-is", config.getHost());
                hosts.add(config.getHost());
            }
            return hosts;
        }

        private void disconnectQuietly(FTPClient client, String scope) {
            try {
                client.disconnect();
            } catch (IOException e) {
                log.warn("FTP disconnect failed ({}): {}", scope, e.getMessage());
            }
        }

        void close() {
            List<FTPClient> allClients = new ArrayList<>();
            lock.lock();
            try {
                for (IdleEntry entry : idle) allClients.add(entry.client);
                allClients.addAll(busy);
                idle.clear();
                busy.clear();
            } finally {
                lock.unlock();
            }
            // 锁外断开（网络 I/O 不在锁内执行）
            for (FTPClient c : allClients) {
                disconnectQuietly(c, "close");
            }
        }
    }
}