package com.tickwise.shared.storage;

import java.io.InputStream;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** S3 API storage: MinIO locally (path-style addressing), any S3-compatible store in production. */
class S3ObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

    private final S3Client s3;
    private final StorageProperties properties;

    S3ObjectStorage(S3Client s3, StorageProperties properties) {
        this.s3 = s3;
        this.properties = properties;
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        s3.putObject(PutObjectRequest.builder().bucket(properties.bucket()).key(key).contentType(contentType).build(),
                RequestBody.fromBytes(content));
    }

    @Override
    public InputStream get(String key) {
        return s3.getObject(GetObjectRequest.builder().bucket(properties.bucket()).key(key).build());
    }

    /** Local convenience: create the bucket if it is missing. Failure is logged, not fatal. */
    @EventListener(ApplicationReadyEvent.class)
    void ensureBucket() {
        if (!properties.createBucket()) {
            return;
        }
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(properties.bucket()).build());
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(properties.bucket()).build());
            log.info("Created storage bucket '{}'", properties.bucket());
        } catch (RuntimeException e) {
            log.warn("Object storage at {} is not reachable: {}", properties.endpoint(), e.getMessage());
        }
    }

    @ConfigurationProperties("tickwise.storage")
    record StorageProperties(String endpoint, String region, String bucket, String accessKey, String secretKey,
                             boolean createBucket) {

        @Override
        public String toString() {
            return "StorageProperties[endpoint=" + endpoint + ", bucket=" + bucket + "]";
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(StorageProperties.class)
    static class Config {

        @Bean(destroyMethod = "close")
        S3Client s3Client(StorageProperties p) {
            var builder = S3Client.builder()
                    .region(Region.of(p.region()))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(p.accessKey(), p.secretKey())))
                    .forcePathStyle(true);
            if (p.endpoint() != null && !p.endpoint().isBlank()) {
                builder.endpointOverride(URI.create(p.endpoint()));
            }
            return builder.build();
        }

        @Bean
        S3ObjectStorage objectStorage(S3Client s3, StorageProperties p) {
            return new S3ObjectStorage(s3, p);
        }
    }
}
