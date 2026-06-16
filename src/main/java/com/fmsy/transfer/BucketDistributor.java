package com.fmsy.transfer;

import com.fmsy.model.Detail;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.CommandRepository;
import com.fmsy.repository.DetailRepository;
import com.fmsy.repository.TargetTableRepository;
import com.fmsy.util.ColumnNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 桶分发器 - 用于DOWNLOAD_MULTI_NODE场景下的数据分桶
 *
 * 功能说明：
 * - getBuckets: 查询明细表中待处理的桶（状态为空）
 * - competeBucket: 竞争桶的处理权
 * - createBuckets: 根据数据分桶值创建桶记录
 * - createChildCommands: 创建S型子命令供各节点竞争
 *
 * DOWNLOAD_MULTI_NODE流程：
 * 1. 主命令查询数据库，按splitFields字段分组获取分桶值
 * 2. 在明细表创建桶记录（对应每组数据的标识）
 * 3. 创建S型子命令（节点通过竞争获取子命令执行权）
 * 4. 各节点处理自己竞争到的桶
 *
 * <p>Phase 1 重构:本类不再持有 SQL,所有数据访问委托给 DetailRepository / CommandRepository.
 * createChildCommands 内部仍组装 EXTRA_INFO 格式("mainId|baseFilePath")。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BucketDistributor {

    private final DetailRepository detailRepository;
    private final CommandRepository commandRepository;
    private final TargetTableRepository targetTableRepository;

    /**
     * 查询待处理的桶列表
     * @param commandId 主命令ID
     * @param limit 返回数量限制
     */
    public List<Detail> getBuckets(Long commandId, int limit) {
        return detailRepository.findBucketsByStatus(commandId, ColumnNames.STATUS_EMPTY, limit);
    }

    /**
     * 竞争桶的处理权
     * @param detailId 明细记录ID
     * @param nodeId 当前节点ID
     * @return 1=竞争成功，0=竞争失败（已被其他节点抢走）
     */
    public int competeBucket(Long detailId, String nodeId) {
        return detailRepository.competeBucket(detailId, nodeId);
    }

    /**
     * 创建桶记录
     * 根据分桶值列表，在明细表插入对应记录
     * @param commandId 主命令ID
     * @param bucketValues 分桶值列表
     * @param splitFields 拆分字段名
     * @param categoryCode 类别代号
     * @param controlCode 控制代号
     */
    public void createBuckets(Long commandId, List<String> bucketValues, String splitFields,
                              String categoryCode, String controlCode) {
        detailRepository.createBuckets(commandId, bucketValues, splitFields, categoryCode, controlCode);
    }

    /**
     * 从目标表 streamQuery DISTINCT 拉分桶值,把多字段拼成 "v1,v2,..." 字符串。
     * 任一字段为 null 的行被丢弃(无法形成有效分桶)。
     *
     * @return 分桶值列表(逗号分隔多字段)
     */
    public List<String> distinctBuckets(TransferConfig config) {
        String splitFields = config.getSplitFields();
        String[] fieldNames = splitFields.split(",");
        List<Map<String, Object>> distinctValues = targetTableRepository.querySmallResult(
                config.getDbName(), config.getTableName(),
                Arrays.asList(fieldNames), true,
                null, null, Arrays.asList(fieldNames), null);
        List<String> bucketValues = new ArrayList<>();
        for (Map<String, Object> row : distinctValues) {
            StringBuilder fv = new StringBuilder();
            boolean allPresent = true;
            for (int i = 0; i < fieldNames.length; i++) {
                Object v = row.get(fieldNames[i].trim());
                if (v == null) {
                    allPresent = false;
                    break;
                }
                if (i > 0) fv.append(',');
                fv.append(v);
            }
            if (allPresent) {
                bucketValues.add(fv.toString());
            }
        }
        return bucketValues;
    }

    /**
     * 创建S型子命令
     * 创建数量与桶数量一致，确保每个桶都有对应的子命令处理
     * 迭代 #17:增加 baseFilePath 参数,序列化到 EXTRA_INFO("mainId|baseFilePath"),
     * 子节点后续可直接读取已固化的基础路径。
     * @param mainCommandId 主命令ID
     * @param categoryCode 类别代号
     * @param controlCode 控制代号
     * @param baseFilePath 主命令已解析的文件路径(允许 null / 空,占位用空串)
     * @return 创建的子命令数量
     */
    public int createChildCommands(Long mainCommandId, String categoryCode, String controlCode,
                                   String baseFilePath) {
        int count = detailRepository.countEmptyBuckets(mainCommandId);
        if (count == 0) {
            log.info("No empty buckets found for command {}, skipping child command creation", mainCommandId);
            return 0;
        }

        // 迭代 #17:把 baseFilePath 嵌入 EXTRA_INFO,格式 "mainId|baseFilePath"
        String pathForExtraInfo = baseFilePath == null ? "" : baseFilePath;
        String extraInfo = mainCommandId.toString() + "|" + pathForExtraInfo;

        int created = 0;
        // 每个桶都要对应一个独立的 S 型子命令(供各节点竞争),因此 N 桶必须 N 行独立 INSERT。
        // 当前 CommandRepository 仅暴露单行 createChildCommand;若后续桶规模扩大,
        // 可在 Repository 加 batchCreateChildCommands 把 N 次 SQL 合成 1 次 batchUpdate。
        for (int i = 0; i < count; i++) {
            int affected = commandRepository.createChildCommand(
                    categoryCode, controlCode, extraInfo, -1);
            created += affected;
        }

        log.info("Created {} S-type child command(s) for main command {} (bucket count: {})",
                created, mainCommandId, count);
        return created;
    }
}
