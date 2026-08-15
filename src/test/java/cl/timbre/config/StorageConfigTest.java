package cl.timbre.config;

import cl.timbre.storage.LocalStorageService;
import cl.timbre.storage.S3StorageService;
import cl.timbre.storage.StorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StorageConfig Integration Tests")
class StorageConfigTest {

    @DisplayName("S3StorageService Creation Tests")
    @SpringJUnitConfig(classes = {StorageConfig.class, StorageProperties.class})
    @TestPropertySource(properties = {
            "storage.provider=s3",
            "storage.bucket=test-bucket",
            "storage.region=us-east-1"
    })
    @Nested
    class S3StorageConfigTests {

        @Autowired
        private StorageService storageService;

        @Test
        @DisplayName("Should create S3StorageService bean when provider=s3")
        void testS3StorageServiceBeanCreation() {
            assertNotNull(storageService);
            assertInstanceOf(S3StorageService.class, storageService);
        }
    }

    @DisplayName("LocalStorageService Creation Tests")
    @SpringJUnitConfig(classes = {StorageConfig.class, StorageProperties.class})
    @TestPropertySource(properties = {
            "storage.provider=local",
            "storage.local-dir=test-storage"
    })
    @Nested
    class LocalStorageConfigTests {

        @Autowired
        private StorageService storageService;

        @Test
        @DisplayName("Should create LocalStorageService bean when provider=local")
        void testLocalStorageServiceBeanCreation() {
            assertNotNull(storageService);
            assertInstanceOf(LocalStorageService.class, storageService);
        }
    }
}
