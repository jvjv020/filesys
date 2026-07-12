package com.fmsy.transfer;

import com.fmsy.config.DataSourceConfig;
import com.fmsy.model.Detail;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.CommandRepository;
import com.fmsy.repository.DetailRepository;
import com.fmsy.repository.TargetTableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BucketDistributor Tests")
class BucketDistributorTest {

    @Mock
    private DetailRepository detailRepository;

    @Mock
    private CommandRepository commandRepository;

    @Mock
    private TargetTableRepository targetTableRepository;

    @Mock
    private DataSourceConfig.DbPool dbPool;

    private BucketDistributor bucketDistributor;

    @BeforeEach
    void setUp() {
        bucketDistributor = new BucketDistributor(
                detailRepository, commandRepository, targetTableRepository, dbPool);
    }

    @Nested
    @DisplayName("distinctBuckets")
    class DistinctBucketsTests {

        @Test
        @DisplayName("should return distinct bucket values from database")
        void shouldReturnDistinctBucketValues() {
            TransferConfig config = new TransferConfig();
            config.setDbName("DB_DEFAULT");
            config.setTableName("test_table");
            config.setSplitFields("REGION");

            List<Map<String, Object>> mockRows = List.of(
                    Map.of("REGION", "EAST"),
                    Map.of("REGION", "WEST")
            );
            when(targetTableRepository.querySmallResult(
                    eq("DB_DEFAULT"), eq("test_table"),
                    anyList(), anyBoolean(),
                    any(), any(),
                    anyList(), any()))
                    .thenReturn(mockRows);

            List<String> result = bucketDistributor.distinctBuckets(config);

            assertEquals(2, result.size());
            assertTrue(result.contains("EAST"));
            assertTrue(result.contains("WEST"));
        }

        @Test
        @DisplayName("should handle multiple split fields concatenated with comma")
        void shouldHandleMultipleSplitFields() {
            TransferConfig config = new TransferConfig();
            config.setDbName("DB_DEFAULT");
            config.setTableName("test_table");
            config.setSplitFields("REGION,STATUS");

            List<Map<String, Object>> mockRows = List.of(
                    Map.of("REGION", "EAST", "STATUS", "ACTIVE"),
                    Map.of("REGION", "WEST", "STATUS", "INACTIVE")
            );
            when(targetTableRepository.querySmallResult(
                    eq("DB_DEFAULT"), eq("test_table"),
                    anyList(), anyBoolean(),
                    any(), any(),
                    anyList(), any()))
                    .thenReturn(mockRows);

            List<String> result = bucketDistributor.distinctBuckets(config);

            assertEquals(2, result.size());
            assertTrue(result.contains("EAST,ACTIVE"));
            assertTrue(result.contains("WEST,INACTIVE"));
        }

        @Test
        @DisplayName("should filter out rows with null values in any split field")
        void shouldFilterOutRowsWithNullValues() {
            TransferConfig config = new TransferConfig();
            config.setDbName("DB_DEFAULT");
            config.setTableName("test_table");
            config.setSplitFields("REGION,STATUS");

            List<Map<String, Object>> mockRows = List.of(
                    createMap("REGION", "EAST", "STATUS", "ACTIVE"),
                    createMap("REGION", null, "STATUS", "INACTIVE"),
                    createMap("REGION", "WEST", "STATUS", null)
            );
            when(targetTableRepository.querySmallResult(
                    eq("DB_DEFAULT"), eq("test_table"),
                    anyList(), anyBoolean(),
                    any(), any(),
                    anyList(), any()))
                    .thenReturn(mockRows);

            List<String> result = bucketDistributor.distinctBuckets(config);

            assertEquals(1, result.size());
            assertEquals("EAST,ACTIVE", result.get(0));
        }

        @Test
        @DisplayName("should return empty list when no data found")
        void shouldReturnEmptyListWhenNoData() {
            TransferConfig config = new TransferConfig();
            config.setDbName("DB_DEFAULT");
            config.setTableName("empty_table");
            config.setSplitFields("REGION");

            when(targetTableRepository.querySmallResult(
                    any(), any(), anyList(), anyBoolean(),
                    any(), any(), anyList(), any()))
                    .thenReturn(List.of());

            List<String> result = bucketDistributor.distinctBuckets(config);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("createBuckets")
    class CreateBucketsTests {

        @Test
        @DisplayName("should delegate bucket creation to detailRepository")
        void shouldDelegateBucketCreation() {
            Long commandId = 100L;
            List<String> bucketValues = Arrays.asList("EAST", "WEST", "NORTH");
            String splitFields = "REGION";
            String categoryCode = "CAT01";
            String controlCode = "CTRL01";

            bucketDistributor.createBuckets(commandId, bucketValues, splitFields, categoryCode, controlCode);

            verify(detailRepository).createBuckets(commandId, bucketValues, splitFields, categoryCode, controlCode);
        }

        @Test
        @DisplayName("should handle empty bucket values list")
        void shouldHandleEmptyBucketValues() {
            Long commandId = 100L;
            List<String> bucketValues = List.of();
            String splitFields = "REGION";

            bucketDistributor.createBuckets(commandId, bucketValues, splitFields, "CAT01", "CTRL01");

            verify(detailRepository).createBuckets(commandId, bucketValues, splitFields, "CAT01", "CTRL01");
        }
    }

    @Nested
    @DisplayName("getBuckets")
    class GetBucketsTests {

        @Test
        @DisplayName("should return buckets from detailRepository")
        void shouldReturnBuckets() {
            Long commandId = 100L;
            int limit = 10;
            Detail bucket1 = new Detail();
            bucket1.setId(1L);
            bucket1.setFieldValue("EAST");
            Detail bucket2 = new Detail();
            bucket2.setId(2L);
            bucket2.setFieldValue("WEST");

            when(detailRepository.findBucketsByStatus(commandId, "", limit))
                    .thenReturn(Arrays.asList(bucket1, bucket2));

            List<Detail> result = bucketDistributor.getBuckets(commandId, limit);

            assertEquals(2, result.size());
            assertEquals("EAST", result.get(0).getFieldValue());
        }
    }

    @Nested
    @DisplayName("competeBucket")
    class CompeteBucketTests {

        @Test
        @DisplayName("should return 1 when competition succeeds")
        void shouldReturn1OnSuccess() {
            Long detailId = 1L;
            String nodeId = "node001";
            when(detailRepository.competeBucket(detailId, nodeId)).thenReturn(1);

            int result = bucketDistributor.competeBucket(detailId, nodeId);

            assertEquals(1, result);
        }

        @Test
        @DisplayName("should return 0 when competition fails")
        void shouldReturn0OnFailure() {
            Long detailId = 1L;
            String nodeId = "node001";
            when(detailRepository.competeBucket(detailId, nodeId)).thenReturn(0);

            int result = bucketDistributor.competeBucket(detailId, nodeId);

            assertEquals(0, result);
        }
    }

    /** Creates a mutable HashMap from key-value pairs (allows null values). */
    private static java.util.Map<String, Object> createMap(Object... kvs) {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        for (int i = 0; i < kvs.length; i += 2) {
            map.put((String) kvs[i], kvs[i + 1]);
        }
        return map;
    }
}
