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
 *   <li>{@code mainCommandId} — S 型子命令关联的主命令 ID(extraInfo)</li>
 *   <li>{@code nodeMainIds} — 节点 → 该节点上正在处理的主命令 ID 集合
 *       (主命令记自身 ID;S 型记其 extraInfo;用于同主 S 型豁免)</li>
 * </ul>
 */
@Data
public class CommandProcessingTracker {

    /** 正在处理该命令的节点集合 */
    private final Map<String, Boolean> nodes = new ConcurrentHashMap<>();

    /** 是否有 S 型子命令 */
    private boolean hasSType = false;

    /** 主命令 ID(S 型子命令时关联到主命令) */
    private String mainCommandId = null;

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
}
