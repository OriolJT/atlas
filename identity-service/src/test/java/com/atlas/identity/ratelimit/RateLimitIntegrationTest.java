package com.atlas.identity.ratelimit;

import com.atlas.identity.TestcontainersConfiguration;
import com.atlas.identity.dto.CreateTenantRequest;
import com.atlas.identity.dto.TenantResponse;
import com.atlas.identity.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the RateLimitFilter returns 429 once the per-tenant bucket is exhausted.
 *
 * Rate limit is set to 2 requests/minute with burst=2 so it is easy to exhaust in test.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "atlas.rate-limit.requests-per-minute=2",
                "atlas.rate-limit.burst-size=2"
        }
)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class RateLimitIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private UUID tenantId;
    private HttpHeaders authHeaders;

    @BeforeEach
    void setUp() {
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        var tenantRequest = new CreateTenantRequest("Rate Limit Tenant " + uniqueId, "rl-" + uniqueId);
        ResponseEntity<TenantResponse> tenantResponse = restTemplate.postForEntity(
                "/api/v1/tenants", tenantRequest, TenantResponse.class);
        assertThat(tenantResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        tenantId = tenantResponse.getBody().tenantId();

        String token = jwtTokenProvider.generateAccessToken(
                UUID.randomUUID(), tenantId, List.of("TENANT_ADMIN"));
        authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth(token);
    }

    @Test
    void whenBucketExhausted_returns429WithRetryAfterHeader() {
        // Fire requests until we get a 429; with burst=2 and rpm=2,
        // the first 2 should succeed and the 3rd should be rate limited.
        int allowed = 0;
        int rateLimited = 0;

        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> response = restTemplate.exchange(
                    "/api/v1/users", HttpMethod.GET,
                    new HttpEntity<>(authHeaders), String.class);

            if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                rateLimited++;
                assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("60");
                assertThat(response.getBody()).contains("ATLAS-COMMON-003");
            } else {
                allowed++;
            }
        }

        assertThat(rateLimited).isGreaterThan(0)
                .withFailMessage("Expected at least one 429 response but got none after 5 requests");
    }
}
