package com.atlas.worker.consumer;

import com.atlas.worker.TestcontainersConfiguration;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, StepCommandConsumerIntegrationTest.TestConfig.class})
class StepCommandConsumerIntegrationTest {

    private static final String EXECUTE_TOPIC = "workflow.steps.execute";
    private static final String RESULT_TOPIC = "workflow.steps.result";

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ResultCapture resultCapture;

    @BeforeEach
    void setUp() {
        resultCapture.clear();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        // Ensure topics exist
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            admin.createTopics(List.of(
                    new NewTopic(EXECUTE_TOPIC, 1, (short) 1),
                    new NewTopic(RESULT_TOPIC, 1, (short) 1)
            ));
        } catch (Exception ignored) {
            // Topics may already exist
        }
    }

    @Test
    void consumeStepCommand_executesAndPublishesResult() {
        Map<String, Object> command = Map.of(
                "step_execution_id", "se-001",
                "execution_id", "exec-001",
                "tenant_id", "tenant-A",
                "step_name", "doSomething",
                "step_type", "INTERNAL_COMMAND",
                "attempt", 1,
                "input", Map.of("key", "value"),
                "timeout_ms", 30000
        );

        kafkaTemplate.send(EXECUTE_TOPIC, "tenant-A", command);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(resultCapture.getResults()).hasSize(1);

            Map<String, Object> result = resultCapture.getResults().get(0);
            assertThat(result.get("step_execution_id")).isEqualTo("se-001");
            assertThat(result.get("outcome")).isEqualTo("SUCCEEDED");
            assertThat(result.get("attempt")).isEqualTo(1);
            assertThat(result.get("non_retryable")).isEqualTo(false);
        });
    }

    @Test
    void consumeStepCommand_unknownStepType_publishesFailedResult() {
        Map<String, Object> command = Map.of(
                "step_execution_id", "se-002",
                "execution_id", "exec-002",
                "tenant_id", "tenant-B",
                "step_name", "unknownStep",
                "step_type", "UNKNOWN_TYPE",
                "attempt", 1,
                "input", Map.of(),
                "timeout_ms", 30000
        );

        kafkaTemplate.send(EXECUTE_TOPIC, "tenant-B", command);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(resultCapture.getResults()).hasSize(1);

            Map<String, Object> result = resultCapture.getResults().get(0);
            assertThat(result.get("step_execution_id")).isEqualTo("se-002");
            assertThat(result.get("outcome")).isEqualTo("FAILED");
            assertThat((String) result.get("error")).contains("No StepExecutor registered for step type");
        });
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        ResultCapture resultCapture() {
            return new ResultCapture();
        }
    }

    /**
     * Test helper bean that captures messages published to the result topic.
     */
    static class ResultCapture {

        private final List<Map<String, Object>> results = new CopyOnWriteArrayList<>();

        @KafkaListener(topics = RESULT_TOPIC, groupId = "test-result-consumer")
        @SuppressWarnings("unchecked")
        public void onResult(Map<String, Object> result) {
            results.add(result);
        }

        public List<Map<String, Object>> getResults() {
            return results;
        }

        public void clear() {
            results.clear();
        }
    }
}
