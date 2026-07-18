package com.fmsy.transfer.download;

import com.fmsy.config.AppConfig;
import com.fmsy.db.PartitionHelper;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.CommandRepository;
import com.fmsy.repository.DetailRepository;
import com.fmsy.repository.TargetTableRepository;
import com.fmsy.util.LogUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 拆分流程服务 — 将目标表按主键序逐块切分为分桶,写入明细表即释放给子节点处理。
 *
 * <p>Plan B 算法:不是一次性获得所有桶边界,而是逐块确认边界后就插入明细表,
 * 让子节点和合流程可以尽早接续工作,形成流水线并行。
 *
 * <p>两种模式:
 * <ul>
 *   <li><b>单文件下发</b>:整表直接按主键序切分,所有桶合并到同一个目标文件</li>
 *   <li><b>拆分下发</b>:先查 DISTINCT 拆分字段值,再逐值按该条件检索主键边界切分,
 *       不同值的目标文件不同</li>
 * </ul>
 *
 * <p>分区表策略:逐分区执行 Plan B 切分。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SplitFlowService {

    private final PartitionHelper partitionHelper;
    private final TargetTableRepository targetTableRepository;
    private final DetailRepository detailRepository;
    private final CommandRepository commandRepository;
    private final AppConfig appConfig;

    /**
     * 同步拆分执行 — 创建 PK 范围桶并写入明细表,完成后标记 splitDone。
     * <p>供 MultiNodeDownloadHandler 在 SERIAL 模式下同步调用,确保桶已创建后再创建 S 子命令。
     */
    public void splitSync(Long mainCommandId, TransferConfig config) {
        LogUtils.setTaskId(mainCommandId);
        doSplit(mainCommandId, config);
        log.info("Sync split completed for command: {}", mainCommandId);
    }

    /**
     * 异步启动拆分流程(适用于单文件下发或拆分下发,根据 config 自动判断)。
     * 完成后自动更新主指令 extra_info 标记拆分完成。
     */
    public void startSplitAsync(Long mainCommandId, TransferConfig config) {
        CompletableFuture.runAsync(() -> {
            LogUtils.setTaskId(mainCommandId);
            try {
                doSplit(mainCommandId, config);
                commandRepository.markSplitDone(mainCommandId);
                log.info("Split flow completed for command: {}", mainCommandId);
            } catch (Exception e) {
                log.error("Split flow failed for command {}: {}", mainCommandId, e.getMessage(), e);
            }
        });
    }

    /** 拆分主逻辑:判断是拆分下发还是单文件下发,分别处理 */
    private void doSplit(Long mainCommandId, TransferConfig config) {
        String dbName = config.getDbName();
        String tableName = config.getTableName();
        int bucketSize = appConfig.getDownload().getBucketRecordSize();
        List<String> pkColumns = partitionHelper.getPrimaryKeyColumns(dbName, tableName);

        if (pkColumns.isEmpty()) {
            throw new IllegalStateException("Table " + tableName + " has no primary key, cannot split");
        }

        String splitFields = config.getSplitFields();
        boolean isSplitDownload = splitFields != null && !splitFields.isEmpty();

        log.info("Split[{}]: table={}, pk={}, split={}, bucketSize={}",
                mainCommandId, tableName, pkColumns, isSplitDownload, bucketSize);

        if (isSplitDownload) {
            // —— 拆分下发:先查 DISTINCT 值,再逐值按条件切分 ——
            List<String> distinctValues = queryDistinctSplitValues(config);
            log.info("Split[{}] distinct split values: {}", mainCommandId, distinctValues.size());
            for (String fieldValue : distinctValues) {
                // 构建按拆分字段筛选的 WHERE 条件
                String extraWhere = buildSplitWhere(splitFields, fieldValue);
                List<Object> extraParams = buildSplitParams(splitFields, fieldValue);

                // 分区表逐分区切分,否则直接切分整表
                List<String> actualTables = resolveActualTables(dbName, tableName);
                List<String> batchSpecs = new ArrayList<>();
                for (String actualTable : actualTables) {
                    splitByPlanB(mainCommandId, dbName, actualTable, pkColumns, bucketSize,
                            extraWhere, extraParams, splitFields, fieldValue, config, batchSpecs);
                }
                // 每处理完一个拆分值就 flush 桶到明细表
                flushBuckets(mainCommandId, batchSpecs, splitFields, fieldValue, config);
            }
        } else {
            // —— 单文件下发:整表直接切分 ——
            List<String> actualTables = resolveActualTables(dbName, tableName);
            List<String> batchSpecs = new ArrayList<>();
            for (String actualTable : actualTables) {
                splitByPlanB(mainCommandId, dbName, actualTable, pkColumns, bucketSize,
                        null, null, null, null, config, batchSpecs);
            }
            flushBuckets(mainCommandId, batchSpecs, null, null, config);
        }
    }

    // ==================== Plan B 逐块切分核心 ====================

    /**
     * Plan B 逐块切分循环:逐块确认 PK 边界就追加到 batchSpecs。
     * 由调用方在适当时候批量写入明细表。
     *
     * @return 创建的桶数量
     */
    private int splitByPlanB(Long mainCommandId, String dbName, String actualTable,
                              List<String> pkColumns, int bucketSize,
                              String extraWhere, List<Object> extraParams,
                              String splitField, String splitValue,
                              TransferConfig config, List<String> batchSpecs) {
        // 获取第一行 PK 值作为起始边界
        Map<String, Object> firstRow = targetTableRepository.queryNextPkBoundary(
                dbName, actualTable, pkColumns, null, 0, extraWhere, extraParams);
        if (firstRow == null) {
            log.warn("Split[{}] no data in table {}", mainCommandId, actualTable);
            return 0;
        }

        List<Object> currentStart = extractPkValuesList(firstRow, pkColumns);
        int count = 0;

        while (true) {
            // 从 currentStart 之后跳过 bucketSize-1 行,得到桶的结束边界
            Map<String, Object> boundary = targetTableRepository.queryNextPkBoundary(
                    dbName, actualTable, pkColumns, currentStart, bucketSize - 1,
                    extraWhere, extraParams);

            String specName;
            if (boundary == null) {
                // 最后一个桶:无上界
                specName = actualTable + "|"
                        + formatPkValues(currentStart) + "|LAST";
                batchSpecs.add(specName);
                count++;
                log.debug("Split[{}] last bucket: {}", mainCommandId, specName);
                break;
            } else {
                List<Object> nextStart = extractPkValuesList(boundary, pkColumns);
                specName = actualTable + "|"
                        + formatPkValues(currentStart) + "|"
                        + formatPkValues(nextStart);
                batchSpecs.add(specName);
                count++;
                currentStart = nextStart;
            }
        }

        log.info("Split[{}] Plan B created {} buckets for {} (split={}={})",
                mainCommandId, count, actualTable, splitField, splitValue);
        return count;
    }

    /** 将一批 specName 写入明细表 */
    private void flushBuckets(Long mainCommandId, List<String> specNames,
                               String splitField, String splitValue,
                               TransferConfig config) {
        if (specNames.isEmpty()) return;
        detailRepository.createBucketsFromSpec(mainCommandId, specNames,
                splitField, splitValue,
                config.getCategoryCode(), config.getControlCode());
        specNames.clear();
    }

    // ==================== 工具方法 ====================

    /** 拆分字段值 → WHERE 条件字符串(如 "REGION = ? AND STATUS = ?") */
    private String buildSplitWhere(String splitFields, String fieldValue) {
        String[] names = splitFields.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) sb.append(" AND ");
            sb.append(names[i].trim()).append(" = ?");
        }
        return sb.toString();
    }

    /** 拆分字段值 → 参数列表 */
    private List<Object> buildSplitParams(String splitFields, String fieldValue) {
        String[] values = fieldValue.split(",");
        return Arrays.stream(values).map(String::trim).collect(Collectors.toList());
    }

    /** 解析实际查询的表名列表(分区表返回分区子表,否则原表) */
    private List<String> resolveActualTables(String dbName, String tableName) {
        if (partitionHelper.isPartitioned(dbName, tableName)) {
            List<String> partitions = partitionHelper.getPartitions(dbName, tableName);
            log.debug("Resolved {} partitions for table {}", partitions.size(), tableName);
            return partitions;
        }
        return List.of(tableName);
    }

    /** 从查询结果 Map 提取 PK 列值列表 */
    private static List<Object> extractPkValuesList(Map<String, Object> row, List<String> pkColumns) {
        List<Object> values = new ArrayList<>();
        for (String col : pkColumns) {
            values.add(row.get(col));
        }
        return values;
    }

    /** PK 值列表 → 逗号拼接字符串(用于 specName) */
    private static String formatPkValues(List<Object> pkValues) {
        return pkValues.stream()
                .map(v -> v != null ? v.toString() : "")
                .collect(Collectors.joining(","));
    }

    /** 查询拆分字段的 DISTINCT 值列表 */
    private List<String> queryDistinctSplitValues(TransferConfig config) {
        String[] fieldNames = config.getSplitFields().split(",");
        List<Map<String, Object>> rows = targetTableRepository.querySmallResult(
                config.getDbName(), config.getTableName(),
                Arrays.asList(fieldNames), true,
                null, null, Arrays.asList(fieldNames), null);
        List<String> values = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            StringBuilder fv = new StringBuilder();
            boolean allPresent = true;
            for (String name : fieldNames) {
                Object v = row.get(name.trim());
                if (v == null) { allPresent = false; break; }
                if (fv.length() > 0) fv.append(',');
                fv.append(v);
            }
            if (allPresent) values.add(fv.toString());
        }
        return values;
    }
}
