package com.fmsy.lifecycle;

import com.fmsy.enums.TransferScenario;
import com.fmsy.exception.TransferException;
import com.fmsy.model.TransferConfig;
import com.fmsy.repository.TransferConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConfigLoaderService Tests")
class ConfigLoaderServiceTest {

    @Mock
    private TransferConfigRepository configRepository;

    private ConfigLoaderService configLoaderService;

    @BeforeEach
    void setUp() {
        configLoaderService = new ConfigLoaderService(configRepository);
    }

    private TransferConfig createConfig(String category, String control) {
        TransferConfig config = new TransferConfig();
        config.setCategoryCode(category);
        config.setControlCode(control);
        config.setScenario(TransferScenario.UPLOAD_SINGLE);
        return config;
    }

    @Nested
    @DisplayName("loadConfigs")
    class LoadConfigsTests {

        @Test
        @DisplayName("should load all configs from repository")
        void shouldLoadAllConfigsFromRepository() {
            TransferConfig config1 = createConfig("CAT1", "CTRL1");
            TransferConfig config2 = createConfig("CAT2", "CTRL2");
            when(configRepository.loadAll()).thenReturn(Arrays.asList(config1, config2));

            configLoaderService.loadConfigs();

            assertNotNull(configLoaderService.getConfig("CAT1", "CTRL1"));
            assertNotNull(configLoaderService.getConfig("CAT2", "CTRL2"));
        }

        @Test
        @DisplayName("should handle empty repository")
        void shouldHandleEmptyRepository() {
            when(configRepository.loadAll()).thenReturn(Collections.emptyList());

            configLoaderService.loadConfigs();

            assertNull(configLoaderService.getConfig("ANY", "ANY"));
        }

        @Test
        @DisplayName("should handle repository exception")
        void shouldHandleRepositoryException() {
            when(configRepository.loadAll()).thenThrow(new RuntimeException("DB error"));

            configLoaderService.loadConfigs();

            assertNull(configLoaderService.getConfig("CAT1", "CTRL1"));
        }
    }

    @Nested
    @DisplayName("getConfig")
    class GetConfigTests {

        @Test
        @DisplayName("should return config when exists")
        void shouldReturnConfigWhenExists() {
            TransferConfig config = createConfig("CAT1", "CTRL1");
            when(configRepository.loadAll()).thenReturn(Collections.singletonList(config));
            configLoaderService.loadConfigs();

            TransferConfig result = configLoaderService.getConfig("CAT1", "CTRL1");

            assertNotNull(result);
            assertEquals("CAT1", result.getCategoryCode());
            assertEquals("CTRL1", result.getControlCode());
        }

        @Test
        @DisplayName("should return null when config does not exist")
        void shouldReturnNullWhenConfigNotExists() {
            when(configRepository.loadAll()).thenReturn(Collections.emptyList());
            configLoaderService.loadConfigs();

            TransferConfig result = configLoaderService.getConfig("NONEXISTENT", "CONFIG");

            assertNull(result);
        }
    }

    @Nested
    @DisplayName("getConfigOrThrow")
    class GetConfigOrThrowTests {

        @Test
        @DisplayName("should return config when exists")
        void shouldReturnConfigWhenExists() {
            TransferConfig config = createConfig("CAT1", "CTRL1");
            when(configRepository.loadAll()).thenReturn(Collections.singletonList(config));
            configLoaderService.loadConfigs();

            TransferConfig result = configLoaderService.getConfigOrThrow("CAT1", "CTRL1");

            assertNotNull(result);
            assertEquals("CAT1", result.getCategoryCode());
        }

        @Test
        @DisplayName("should throw TransferException when config not found")
        void shouldThrowWhenConfigNotFound() {
            when(configRepository.loadAll()).thenReturn(Collections.emptyList());
            configLoaderService.loadConfigs();

            TransferException exception = assertThrows(TransferException.class,
                () -> configLoaderService.getConfigOrThrow("NONEXISTENT", "CONFIG"));

            assertEquals("CONFIG_NOT_FOUND", exception.getErrorCode());
            assertTrue(exception.getMessage().contains("NONEXISTENT_CONFIG"));
        }
    }

    @Nested
    @DisplayName("getConfigOrDefault")
    class GetConfigOrDefaultTests {

        @Test
        @DisplayName("should return config when exists")
        void shouldReturnConfigWhenExists() {
            TransferConfig config = createConfig("CAT1", "CTRL1");
            when(configRepository.loadAll()).thenReturn(Collections.singletonList(config));
            configLoaderService.loadConfigs();

            TransferConfig result = configLoaderService.getConfigOrDefault("CAT1", "CTRL1");

            assertNotNull(result);
            assertEquals("CAT1", result.getCategoryCode());
        }

        @Test
        @DisplayName("should return null when config not exists (no exception)")
        void shouldReturnNullWhenConfigNotExists() {
            when(configRepository.loadAll()).thenReturn(Collections.emptyList());
            configLoaderService.loadConfigs();

            TransferConfig result = configLoaderService.getConfigOrDefault("NONEXISTENT", "CONFIG");

            assertNull(result);
        }

        @Test
        @DisplayName("should not throw when config not exists")
        void shouldNotThrowWhenConfigNotExists() {
            when(configRepository.loadAll()).thenReturn(Collections.emptyList());
            configLoaderService.loadConfigs();

            assertDoesNotThrow(() -> configLoaderService.getConfigOrDefault("NONEXISTENT", "CONFIG"));
        }
    }

    @Nested
    @DisplayName("config key uniqueness")
    class ConfigKeyUniquenessTests {

        @Test
        @DisplayName("should use categoryCode_controlCode as key")
        void shouldUseCategoryCodeControlCodeAsKey() {
            TransferConfig config1 = createConfig("CAT", "A");
            TransferConfig config2 = createConfig("CAT", "B");
            when(configRepository.loadAll()).thenReturn(Arrays.asList(config1, config2));
            configLoaderService.loadConfigs();

            assertNotNull(configLoaderService.getConfig("CAT", "A"));
            assertNotNull(configLoaderService.getConfig("CAT", "B"));
            assertNull(configLoaderService.getConfig("CAT", "C"));
        }

        @Test
        @DisplayName("should allow same control code with different category")
        void shouldAllowSameControlWithDifferentCategory() {
            TransferConfig config1 = createConfig("CAT1", "CTRL");
            TransferConfig config2 = createConfig("CAT2", "CTRL");
            when(configRepository.loadAll()).thenReturn(Arrays.asList(config1, config2));
            configLoaderService.loadConfigs();

            assertNotNull(configLoaderService.getConfig("CAT1", "CTRL"));
            assertNotNull(configLoaderService.getConfig("CAT2", "CTRL"));
        }
    }

    @Nested
    @DisplayName("config loading sequence")
    class ConfigLoadingSequenceTests {

        @Test
        @DisplayName("should load configs only when loadConfigs is called")
        void shouldLoadConfigsOnlyWhenCalled() {
            when(configRepository.loadAll()).thenReturn(Collections.emptyList());

            assertNull(configLoaderService.getConfig("CAT1", "CTRL1"));

            configLoaderService.loadConfigs();

            assertNull(configLoaderService.getConfig("CAT1", "CTRL1"));
        }

        @Test
        @DisplayName("should preserve loaded configs across multiple getConfig calls")
        void shouldPreserveLoadedConfigs() {
            TransferConfig config = createConfig("CAT1", "CTRL1");
            when(configRepository.loadAll()).thenReturn(Collections.singletonList(config));
            configLoaderService.loadConfigs();

            TransferConfig result1 = configLoaderService.getConfig("CAT1", "CTRL1");
            TransferConfig result2 = configLoaderService.getConfig("CAT1", "CTRL1");

            assertSame(result1, result2);
        }
    }
}
