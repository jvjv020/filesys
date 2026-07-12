package com.fmsy.transfer.download;

import com.fmsy.config.AppConfig;
import com.fmsy.converter.FileConverter;
import com.fmsy.db.PartitionHelper;
import com.fmsy.model.FieldMapping;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.TargetTableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ParallelFileGenerator Tests")
class ParallelFileGeneratorTest {

    @Mock
    private TargetTableRepository targetTableRepository;

    @Mock
    private PartitionHelper partitionHelper;

    @Mock
    private AppConfig appConfig;

    @Mock
    private AppConfig.Download download;

    @Mock
    private FileConverter fileConverter;

    @Mock
    private FieldMapping mapping;

    private ParallelFileGenerator generator;

    @BeforeEach
    void setUp() {
        when(appConfig.getDownload()).thenReturn(download);
        when(download.getParallelThreads()).thenReturn(3);
        generator = new ParallelFileGenerator(targetTableRepository, partitionHelper, appConfig);
    }

    @Nested
    @DisplayName("generate - serial fallback")
    class GenerateSerialFallback {

        @Test
        @DisplayName("should generate serial when table is not partitioned")
        void shouldGenerateSerialWhenNotPartitioned() {
            TransferConfig config = new TransferConfig();
            config.setDbName("DB1");
            config.setTableName("mytable");

            when(partitionHelper.isPartitioned("DB1", "mytable")).thenReturn(false);
            when(targetTableRepository.streamQueryBatches(
                    anyString(), anyString(), isNull(), eq(false),
                    isNull(), isNull(), isNull(), isNull()))
                    .thenReturn(mock(TargetTableRepository.DataStream.class));
            when(fileConverter.writeDataRecords(any(OutputStream.class),
                    any(Iterator.class), eq(mapping))).thenReturn(50);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int count = generator.generate(output, config, fileConverter, mapping, 0);

            assertTrue(count >= 0);
            verify(fileConverter).writeHeader(output, mapping, 0);
            verify(fileConverter).writeFooter(output, mapping);
        }

        @Test
        @DisplayName("should generate serial when partition count < 2")
        void shouldGenerateSerialWhenPartitionCountLessThan2() {
            TransferConfig config = new TransferConfig();
            config.setDbName("DB1");
            config.setTableName("mytable");

            when(partitionHelper.isPartitioned("DB1", "mytable")).thenReturn(true);
            when(partitionHelper.getPartitions("DB1", "mytable")).thenReturn(List.of("part1"));
            when(targetTableRepository.streamQueryBatches(
                    anyString(), anyString(), isNull(), eq(false),
                    isNull(), isNull(), isNull(), isNull()))
                    .thenReturn(mock(TargetTableRepository.DataStream.class));
            when(fileConverter.writeDataRecords(any(OutputStream.class),
                    any(Iterator.class), eq(mapping))).thenReturn(30);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int count = generator.generate(output, config, fileConverter, mapping, 0);

            assertEquals(30, count);
            verify(fileConverter).writeHeader(output, mapping, 0);
        }

        @Test
        @DisplayName("should pass preCountedRecords to writeHeader in serial mode")
        void shouldPassPreCountedRecordsToWriteHeader() {
            TransferConfig config = new TransferConfig();
            config.setDbName("DB1");
            config.setTableName("mytable");

            when(partitionHelper.isPartitioned("DB1", "mytable")).thenReturn(false);
            when(targetTableRepository.streamQueryBatches(
                    anyString(), anyString(), isNull(), eq(false),
                    isNull(), isNull(), isNull(), isNull()))
                    .thenReturn(mock(TargetTableRepository.DataStream.class));
            when(fileConverter.writeDataRecords(any(OutputStream.class),
                    any(Iterator.class), eq(mapping))).thenReturn(100);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            generator.generate(output, config, fileConverter, mapping, 500);

            verify(fileConverter).writeHeader(output, mapping, 500);
        }
    }

    @Nested
    @DisplayName("generate - parallel mode")
    class GenerateParallelMode {

        @Test
        @DisplayName("should generate in parallel for partitioned tables")
        void shouldGenerateParallelForPartitionedTables() {
            TransferConfig config = new TransferConfig();
            config.setDbName("DB1");
            config.setTableName("mytable");
            config.setCategoryCode("CAT");
            config.setControlCode("CTRL");

            when(partitionHelper.isPartitioned("DB1", "mytable")).thenReturn(true);
            when(partitionHelper.getPartitions("DB1", "mytable"))
                    .thenReturn(List.of("part1", "part2", "part3"));
            when(partitionHelper.getPrimaryKeyColumns("DB1", "mytable")).thenReturn(List.of("id"));
            when(targetTableRepository.streamTableDirect(anyString(), anyString(), anyList()))
                    .thenReturn(mock(TargetTableRepository.DataStream.class));
            when(fileConverter.writeDataRecords(any(OutputStream.class),
                    any(Iterator.class), eq(mapping))).thenReturn(30);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int count = generator.generate(output, config, fileConverter, mapping, 0);

            assertTrue(count >= 0);
            verify(fileConverter).writeHeader(output, mapping, 0);
            verify(fileConverter).writeFooter(output, mapping);
        }

        @Test
        @DisplayName("should handle parallel generation with preCountedRecords")
        void shouldHandleParallelWithPreCountedRecords() {
            TransferConfig config = new TransferConfig();
            config.setDbName("DB1");
            config.setTableName("mytable");
            config.setCategoryCode("CAT");
            config.setControlCode("CTRL");

            when(partitionHelper.isPartitioned("DB1", "mytable")).thenReturn(true);
            when(partitionHelper.getPartitions("DB1", "mytable"))
                    .thenReturn(List.of("part1", "part2"));
            when(partitionHelper.getPrimaryKeyColumns("DB1", "mytable")).thenReturn(List.of("id"));
            when(targetTableRepository.streamTableDirect(anyString(), anyString(), anyList()))
                    .thenReturn(mock(TargetTableRepository.DataStream.class));
            when(fileConverter.writeDataRecords(any(OutputStream.class),
                    any(Iterator.class), eq(mapping))).thenReturn(40);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int count = generator.generate(output, config, fileConverter, mapping, 100);

            assertEquals(80, count);
            verify(fileConverter).writeHeader(output, mapping, 100);
        }
    }
}
