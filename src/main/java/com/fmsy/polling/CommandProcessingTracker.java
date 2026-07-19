package com.fmsy.polling;

import lombok.Data;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 命令处理跟踪器 — 记录某"类别代号+控制代号"维度的命令正在哪些节点处理。
 *
 * <p>由 {@link PollingService#loadProcessingCommands()} 每轮从 DB 重建,供
 * {@link SerialConstraintChecker} 判断同 key 是否可并发。
 *
 * <p>字段:
 * <ul>
 *   <li>{@code nodes} — 正在处理该 key 的节点集合(用于 S 型/串行模式去重)</li>
 *   <li>{@code hasSType} — 是否有 S 型子命令(影响并发豁免判定)</li>
 *   <li>{@code nodeMainIds} — 节点 → 该节点上正在处理的主命令 ID 集合
 *       (主命令记自身 ID;S 型记其 extraInfo;用于同主 S 型豁免)</li>
 * </ul>
 *
 * <p>注意:原来有单独的 {@code mainCommandId} 单值字段,但由于同一 key 下可能
 * 有多个主命令的 S 子命令并发处理,单值字段会被覆盖。改为统一从 {@code nodeMainIds}
 * 跨节点查询,避免覆盖问题。
 */
@Data
public class CommandProcessingTracker {

    /** 正在处理该命令的节点集合 */
    private final Map<String, Boolean> nodes = new ConcurrentHashMap<>();

    /** 是否有 S 型子命令 */
    private boolean hasSType = false;

    /** 节点 → 该节点上正在处理的主命令 ID 集合 */
    private final Map<String, Set<String>> nodeMainIds = new ConcurrentHashMap<>();

    public void addNode(String nodeId) {
        nodes.put(nodeId, true);
    }

    public boolean hasNode(String nodeId) {
        return nodes.containsKey(nodeId);
    }

    /**
     * 记录某节点上正在处理的主命令 ID
     */
    public void recordMainId(String nodeId, String mainId) {
        if (nodeId == null || mainId == null) {
            return;
        }
        nodeMainIds.computeIfAbsent(nodeId, k -> ConcurrentHashMap.newKeySet()).add(mainId);
    }

    /**
     * 判断某节点上是否正在处理指定主命令
     */
    public boolean hasMainId(String nodeId, String mainId) {
        if (nodeId == null || mainId == null) {
            return false;
        }
        Set<String> set = nodeMainIds.get(nodeId);
        return set != null && set.contains(mainId);
    }

    /**
     * 判断是否有任意节点正在处理指定主命令。
     * 替代原来的单值 {@code mainCommandId} 字段,避免多条 S 子命令覆盖问题。
     */
    public boolean hasMainCommandId(String mainId) {
        if (mainId == null) return false;
        for (Set<String> ids : nodeMainIds.values()) {
            if (ids.contains(mainId)) return true;
        }
        return false;
    }

    /**
     * 设置主命令 ID（兼容旧测试）。
     * @deprecated 使用 {@link #recordMainId(String, String)} 替代
     */
    @Deprecated
    public void setMainCommandId(String mainId) {
        recordMainId("node1", mainId);
    }

    /**
     * 获取主命令 ID（兼容旧测试，返回第一个找到的）。
     * @deprecated 使用 {@link #hasMainId(String, String)} 替代
     */
    @Deprecated
    public String getMainCommandId() {
        for (Set<String> ids : nodeMainIds.values()) {
            if (!ids.isEmpty()) return ids.iterator().next();
        }
        return null;
    }
}
