package cl.timbre.config;

import cl.timbre.storage.LocalStorageService;
import cl.timbre.storage.S3StorageService;
import cl.timbre.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

/**
 * Spring configuration for storage provider setup.
 * Creates StorageService bean based on the storage.provider property.
 * Supports: "s3" (AWS S3/R2/MinIO) and "local" (filesystem for dev/test).
 */
@Configuration
public class StorageConfig {

    private static final Logger logger = LoggerFactory.getLogger(StorageConfig.class);

    private final StorageProperties storageProperties;

    public StorageConfig(StorageProperties storageProperties) {
        this.storageProperties = storageProperties;
    }

    /**
     * Creates an S3StorageService bean when storage.provider=s3.
     *
     * @return S3StorageService configured with S3Client and bucket name
     */
    @Bean
    @ConditionalOnProperty(name = "storage.provider", havingValue = "s3")
    public StorageService s3StorageService() {
        logger.info("Initializing S3StorageService with provider=s3");

        S3ClientBuilder builder = S3Client.builder()
                .region(resolveRegion(storageProperties.getRegion()));

        // Set endpoint if provided (for R2, MinIO, or other S3-compatible services)
        if (storageProperties.getEndpoint() != null && !storageProperties.getEndpoint().isEmpty()) {
            logger.info("Configuring S3Client with custom endpoint: {}", storageProperties.getEndpoint());
            builder.endpointOverride(URI.create(storageProperties.getEndpoint()));
        }

        // Set credentials if provided
        if (storageProperties.getAccessKey() != null && storageProperties.getSecretKey() != null) {
            logger.debug("Configuring S3Client with provided credentials");
            AwsBasicCredentials credentials = AwsBasicCredentials.create(
                    storageProperties.getAccessKey(),
                    storageProperties.getSecretKey()
            );
            builder.credentialsProvider(StaticCredentialsProvider.create(credentials));
        }

        S3Client s3Client = builder.build();
        logger.info("S3StorageService initialized with bucket: {}", storageProperties.getBucket());
        return new S3StorageService(s3Client, storageProperties.getBucket());
    }

    /**
     * Creates a LocalStorageService bean when storage.provider=local.
     *
     * @return LocalStorageService configured with local base directory
     */
    @Bean
    @ConditionalOnProperty(name = "storage.provider", havingValue = "local")
    public StorageService localStorageService() {
        logger.info("Initializing LocalStorageService with provider=local");

        String localDir = storageProperties.getLocalDir();
        if (localDir == null || localDir.isEmpty()) {
            localDir = "storage";
            logger.warn("No local-dir configured; using default: {}", localDir);
        }

        logger.info("LocalStorageService initialized with local directory: {}", localDir);
        return new LocalStorageService(localDir);
    }

    /**
     * Resolves AWS region from configuration.
     * Handles special case "auto" for R2 (which manages regions automatically).
     *
     * @param regionString the region string from configuration
     * @return Region object; defaults to us-east-1 if "auto" or unknown value
     */
    private Region resolveRegion(String regionString) {
        if (regionString == null || regionString.isEmpty() || "auto".equals(regionString)) {
            logger.debug("Region resolved to us-east-1 (auto or unspecified)");
            return Region.US_EAST_1;
        }

        try {
            Region region = Region.of(regionString);
            logger.debug("Region resolved to: {}", regionString);
            return region;
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown region '{}', defaulting to us-east-1", regionString);
            return Region.US_EAST_1;
        }
    }
}
