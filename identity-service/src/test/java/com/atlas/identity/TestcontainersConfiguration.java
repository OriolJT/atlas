package com.atlas.identity;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    public PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:17")
                .withDatabaseName("atlas_test")
                .withUsername("atlas_test")
                .withPassword("atlas_test");
    }

    @Bean
    @ServiceConnection
    public ConfluentKafkaContainer kafkaContainer() {
        return new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.0");
    }

    @Bean
    @ServiceConnection(name = "redis")
    public GenericContainer redisContainer() {
        return new GenericContainer("redis:7-alpine")
                .withExposedPorts(6379);
    }
}
