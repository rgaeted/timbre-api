package cl.timbre.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for storage provider setup.
 * Reads from application.yml under the 'storage' namespace.
 *
 * Example configuration:
 * storage:
 *   provider: s3
 *   bucket: my-bucket
 *   endpoint: https://r2.example.com
 *   region: us-east-1
 *   access-key: ${R2_ACCESS_KEY}
 *   secret-key: ${R2_SECRET_KEY}
 *   local-dir: storage
 */
@Component
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    private String provider;           // "s3" or "local"
    private String bucket;             // S3/R2 bucket name
    private String endpoint;           // S3/R2 endpoint URL (optional)
    private String region;             // AWS region or "auto" for R2
    private String accessKey;          // AWS/R2 access key
    private String secretKey;          // AWS/R2 secret key
    private String localDir;           // Local filesystem base directory (for provider=local)

    public StorageProperties() {
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getLocalDir() {
        return localDir;
    }

    public void setLocalDir(String localDir) {
        this.localDir = localDir;
    }

    @Override
    public String toString() {
        return "StorageProperties{" +
                "provider='" + provider + '\'' +
                ", bucket='" + bucket + '\'' +
                ", endpoint='" + endpoint + '\'' +
                ", region='" + region + '\'' +
                ", accessKey='" + (accessKey != null ? "***" : "null") + '\'' +
                ", secretKey='" + (secretKey != null ? "***" : "null") + '\'' +
                ", localDir='" + localDir + '\'' +
                '}';
    }
}
