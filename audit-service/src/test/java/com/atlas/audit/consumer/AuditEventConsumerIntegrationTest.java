package com.atlas.audit.consumer;

import com.atlas.audit.TestcontainersConfiguration;
import com.atlas.audit.repository.AuditEventRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AuditEventConsumerIntegrationTest {

    private static final String AUDIT_TOPIC = "audit.events";

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @BeforeEach
    void setUp() {
        auditEventRepository.deleteAll();

        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            admin.createTopics(List.of(new NewTopic(AUDIT_TOPIC, 1, (short) 1)));
        } catch (Exception ignored) {
            // Topic may already exist
        }
    }

    @Test
    void consumeAuditEvent_persistsToDatabase() {
        UUID auditEventId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        String occurredAt = Instant.now().toString();

        Map<String, Object> event = Map.of(
                "audit_event_id", auditEventId.toString(),
                "tenant_id", tenantId.toString(),
                "actor_type", "USER",
                "actor_id", actorId.toString(),
                "event_type", "USER_LOGIN",
                "resource_type", "USER",
                "resource_id", resourceId.toString(),
                "payload", Map.of("ip", "192.168.1.1"),
                "correlation_id", correlationId.toString(),
                "occurred_at", occurredAt
        );

        kafkaTemplate.send(AUDIT_TOPIC, tenantId.toString(), event);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            var saved = auditEventRepository.findById(auditEventId);
            assertThat(saved).isPresent();
            assertThat(saved.get().getTenantId()).isEqualTo(tenantId);
            assertThat(saved.get().getActorType()).isEqualTo("USER");
            assertThat(saved.get().getActorId()).isEqualTo(actorId);
            assertThat(saved.get().getEventType()).isEqualTo("USER_LOGIN");
            assertThat(saved.get().getResourceType()).isEqualTo("USER");
            assertThat(saved.get().getResourceId()).isEqualTo(resourceId);
            assertThat(saved.get().getCorrelationId()).isEqualTo(correlationId);
            assertThat(saved.get().getPayload()).containsEntry("ip", "192.168.1.1");
        });
    }

    @Test
    void consumeAuditEvent_duplicateEventId_noDoubleInsert() {
        UUID auditEventId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String occurredAt = Instant.now().toString();

        Map<String, Object> event = Map.of(
                "audit_event_id", auditEventId.toString(),
                "tenant_id", tenantId.toString(),
                "actor_type", "SERVICE",
                "event_type", "ORDER_CREATED",
                "resource_type", "ORDER",
                "occurred_at", occurredAt
        );

        kafkaTemplate.send(AUDIT_TOPIC, tenantId.toString(), event);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(auditEventRepository.findById(auditEventId)).isPresent());

        // Publish the same event again
        kafkaTemplate.send(AUDIT_TOPIC, tenantId.toString(), event);

        // Wait briefly, then assert still exactly one record
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(auditEventRepository.count()).isEqualTo(1L));
    }
}
