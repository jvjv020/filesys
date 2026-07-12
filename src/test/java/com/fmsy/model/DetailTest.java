package com.fmsy.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Detail Tests")
class DetailTest {

    @Nested
    @DisplayName("field getter/setter")
    class FieldTests {

        @Test
        @DisplayName("should store and retrieve id")
        void shouldStoreAndRetrieveId() {
            Detail detail = new Detail();
            detail.setId(1L);
            assertEquals(1L, detail.getId());
        }

        @Test
        @DisplayName("should store and retrieve commandId")
        void shouldStoreAndRetrieveCommandId() {
            Detail detail = new Detail();
            detail.setCommandId(100L);
            assertEquals(100L, detail.getCommandId());
        }

        @Test
        @DisplayName("should store and retrieve categoryCode")
        void shouldStoreAndRetrieveCategoryCode() {
            Detail detail = new Detail();
            detail.setCategoryCode("CAT001");
            assertEquals("CAT001", detail.getCategoryCode());
        }

        @Test
        @DisplayName("should store and retrieve controlCode")
        void shouldStoreAndRetrieveControlCode() {
            Detail detail = new Detail();
            detail.setControlCode("CTRL001");
            assertEquals("CTRL001", detail.getControlCode());
        }

        @Test
        @DisplayName("should store and retrieve fieldValue")
        void shouldStoreAndRetrieveFieldValue() {
            Detail detail = new Detail();
            detail.setFieldValue("BUCKET_EAST");
            assertEquals("BUCKET_EAST", detail.getFieldValue());
        }

        @Test
        @DisplayName("should store and retrieve auditCount")
        void shouldStoreAndRetrieveAuditCount() {
            Detail detail = new Detail();
            detail.setAuditCount(500);
            assertEquals(500, detail.getAuditCount());
        }

        @Test
        @DisplayName("should store and retrieve status")
        void shouldStoreAndRetrieveStatus() {
            Detail detail = new Detail();
            detail.setStatus("Y");
            assertEquals("Y", detail.getStatus());
        }
    }
}
