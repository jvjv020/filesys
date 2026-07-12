package com.fmsy.converter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConverterFactory Tests")
class ConverterFactoryTest {

    private ConverterFactory factory;

    @BeforeEach
    void setUp() {
        // Create factory with all available converters
        CsvConverter csvConverter = new CsvConverter();
        TxtConverter txtConverter = new TxtConverter();
        // Note: DbfConverter and XmlConverter require external dependencies,
        // so we test with only CSV and TXT converters
        factory = new ConverterFactory(List.of(csvConverter, txtConverter));
        factory.init();
    }

    @Nested
    @DisplayName("get")
    class GetTests {

        @Test
        @DisplayName("should return CSV converter for CSV parser type")
        void shouldReturnCsvConverterForCsvParserType() {
            FileConverter converter = factory.get("CSV");

            assertNotNull(converter);
            assertEquals("CSV", converter.getFormat());
        }

        @Test
        @DisplayName("should return TXT converter for TXT parser type")
        void shouldReturnTxtConverterForTxtParserType() {
            FileConverter converter = factory.get("TXT");

            assertNotNull(converter);
            assertEquals("TXT", converter.getFormat());
        }

        @Test
        @DisplayName("should throw IllegalArgumentException for unsupported parser type")
        void shouldThrowIllegalArgumentExceptionForUnsupportedParserType() {
            assertThrows(IllegalArgumentException.class, () -> factory.get("UNSUPPORTED"));
        }

        @Test
        @DisplayName("should throw IllegalArgumentException for null parser type")
        void shouldThrowIllegalArgumentExceptionForNullParserType() {
            assertThrows(IllegalArgumentException.class, () -> factory.get(null));
        }
    }

    @Nested
    @DisplayName("init")
    class InitTests {

        @Test
        @DisplayName("should initialize converter map successfully")
        void shouldInitializeConverterMapSuccessfully() {
            ConverterFactory newFactory = new ConverterFactory(List.of(new CsvConverter()));
            newFactory.init();

            FileConverter converter = newFactory.get("CSV");
            assertNotNull(converter);
        }

        @Test
        @DisplayName("should handle duplicate converter formats gracefully")
        void shouldHandleDuplicateConverterFormatsGracefully() {
            // When duplicates exist, the first one should be kept
            CsvConverter csv1 = new CsvConverter();
            CsvConverter csv2 = new CsvConverter();
            ConverterFactory factoryWithDuplicates = new ConverterFactory(List.of(csv1, csv2));

            // Should not throw, just log a warning
            assertDoesNotThrow(() -> factoryWithDuplicates.init());
        }
    }
}
