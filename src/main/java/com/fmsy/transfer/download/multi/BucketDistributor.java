package com.fmsy.transfer.download.multi;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.model.Command;
import com.fmsy.model.Detail;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.CommandRepository;
import com.fmsy.repository.DetailRepository;
import com.fmsy.repository.TargetTableRepository;
import com.fmsy.transfer.SplitFieldHelper;
import com.fmsy.util.ColumnNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 桶分发器 - 用于 DOWNLOAD_MULTI_NODE / DOWNLOAD_SINGLE_NODE 场景下的数据分桶。
 *
 * <p>功能说明：
 * <ul>
 *   <li>{@link #getBuckets} — 查询明细表中待处理的桶（状态为空）</li>
 *   <li>{@link #competeBucket} — 竞争桶的处理权</li>
 *   <li>{@link #createBuckets} — 根据数据分桶值创建桶记录</li>
 *   <li>{@link #createChildCommands} — 创建 S 型子命令供各节点竞争</li>
 * </ul>
 *
 * <p>DOWNLOAD_MULTI_NODE 流程：
 * <ol>
 *   <li>主命令查询数据库，按 splitFields 字段分组获取分桶值</li>
 *   <li>在明细表创建桶记录（对应每组数据的标识）</li>
 *   <li>创建 S 型子命令（节点通过竞争获取子命令执行权）</li>
 *   <li>各节点处理自己竞争到的桶</li>
 * </ol>
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
    private final DataSourceConfig.DbPool dbPool;

    /**
     * 查询待处理的桶列表
     *
     * @param commandId 主命令ID
     * @param limit     返回数量限制
     */
    public List<Detail> getBuckets(Long commandId, int limit) {
        return detailRepository.findBucketsByStatus(commandId, ColumnNames.STATUS_EMPTY, limit);
    }

    /**
     * 竞争桶的处理权
     *
     * @param detailId 明细记录ID
     * @param nodeId   当前节点ID
     * @return 1=竞争成功，0=竞争失败（已被其他节点抢走）
     */
    public int competeBucket(Long detailId, String nodeId) {
        return detailRepository.competeBucket(detailId, nodeId);
    }

    /**
     * 创建桶记录
     * 根据分桶值列表，在明细表插入对应记录
     *
     * @param commandId    主命令ID
     * @param bucketValues 分桶值列表
     * @param splitFields  拆分字段名
     * @param categoryCode 类别代号
     * @param controlCode  控制代号
     */
    public void createBuckets(Long commandId, List<String> bucketValues, String splitFields,
            String categoryCode, String controlCode) {
        detailRepository.createBuckets(commandId, bucketValues, splitFields, categoryCode, controlCode);
    }

    /**
     * 从目标表 streamQuery DISTINCT 拉分桶值,把多字段拼成 "v1,v2,..." 字符串。
     * 任一字段为 null 的行被丢弃(无法形成有效分桶)。
     *
     * <p>
     * 委托给 {@link SplitFieldHelper#queryDistinctBuckets}。
     * </p>
     *
     * @return 分桶值列表(逗号分隔多字段)
     */
    public List<String> distinctBuckets(TransferConfig config) {
        return SplitFieldHelper.queryDistinctBuckets(targetTableRepository, config);
    }

    /**
     * 创建S型子命令
     * 创建数量与桶数量一致，确保每个桶都有对应的子命令处理
     * 迭代 #17:增加 baseFilePath 参数,序列化到 EXTRA_INFO("mainId|baseFilePath"),
     * 子节点后续可直接读取已固化的基础路径。
     *
     * @param mainCommandId 主命令ID
     * @param categoryCode  类别代号
     * @param controlCode   控制代号
     * @param baseFilePath  主命令已解析的文件路径(允许 null / 空,占位用空串)
     * @return 创建的子命令数量
     */
    public int createChildCommands(Long mainCommandId, String categoryCode, String controlCode,
            String baseFilePath) {
        int count = detailRepository.countEmptyBuckets(mainCommandId);
        if (count == 0) {
            log.info("No empty buckets found for command {}, skipping child command creation", mainCommandId);
            return 0;
        }

        String extraInfo = mainCommandId.toString() + "|"
                + (baseFilePath != null ? baseFilePath : "");

        if (count > 0) {
            commandRepository.batchCreateChildCommands(count, categoryCode, controlCode, extraInfo, -1);
        }

        log.info("Created {} S-type child command(s) for main command {} (bucket count: {})",
                count, mainCommandId, count);
        return count;
    }

    /**
     * BATCH 模式:逐桶预统计 auditCount 后创建 S 型子命令。
     * <p>
     * 整段包在事务中:任一桶的 audit_count 写入或任一子命令创建失败 → 全部回滚,
     * 避免"已有桶审计数已更新但子命令半创建"的中间态。
     *
     * @param command      主命令
     * @param config       传输配置
     * @param baseFilePath 基础文件路径
     * @param splitFields  拆分字段名
     * @return 创建的子命令数量(0 表示无桶或无数据)
     */
    public int prepareBatchChildren(Command command, TransferConfig config,
            String baseFilePath, String splitFields) {
        List<Detail> existingBuckets = detailRepository.findByCommandId(command.getId());
        if (existingBuckets.isEmpty()) {
            log.info("No existing buckets found for BATCH command: {}", command.getId());
            return 0;
        }

        return dbPool.getTransactionTemplate(config.getDbName()).execute(status -> {
            // 桶数多时批量查询,减少 SQL 往返
            if (existingBuckets.size() >= 3) {
                List<String> fieldValues = existingBuckets.stream()
                        .map(Detail::getFieldValue)
                        .toList();
                Map<String, Integer> counts = targetTableRepository.countByBuckets(
                        config.getDbName(), config.getTableName(), splitFields, fieldValues);
                for (Detail bucket : existingBuckets) {
                    int recordCount = counts.getOrDefault(bucket.getFieldValue(), 0);
                    detailRepository.updateAuditCount(bucket.getId(), recordCount);
                }
            } else {
                // 桶数少时逐桶查询
                for (Detail bucket : existingBuckets) {
                    int recordCount = targetTableRepository.countByBucket(
                            config.getDbName(), config.getTableName(), splitFields, bucket.getFieldValue());
                    detailRepository.updateAuditCount(bucket.getId(), recordCount);
                }
            }

            return createChildCommands(command.getId(),
                    config.getCategoryCode(), config.getControlCode(), baseFilePath);
        });
    }

    /**
     * SERIAL 模式:从目标表拉 distinct 分桶值 → 写桶 → 创建 S 型子命令。
     * <p>
     * createBuckets + createChildCommands 包在事务中:避免"桶已写但子命令未创建"
     * 的孤儿状态(孤儿桶永久无主,只能人工清理)。
     *
     * @param command      主命令
     * @param config       传输配置
     * @param baseFilePath 基础文件路径
     * @param splitFields  拆分字段名
     * @return 创建的子命令数量(0 表示无数据)
     */
    public int prepareSerialChildren(Command command, TransferConfig config,
            String baseFilePath, String splitFields) {
        List<String> bucketValues = distinctBuckets(config);
        if (bucketValues.isEmpty()) {
            log.info("No data found for command: {}", command.getId());
            return 0;
        }

        return dbPool.getTransactionTemplate(config.getDbName()).execute(status -> {
            createBuckets(command.getId(), bucketValues, splitFields,
                    config.getCategoryCode(), config.getControlCode());
            return createChildCommands(command.getId(),
                    config.getCategoryCode(), config.getControlCode(), baseFilePath);
        });
    }
}