package com.atlas.identity.service;

import com.atlas.identity.TestcontainersConfiguration;
import com.atlas.identity.domain.ApiKey;
import com.atlas.identity.domain.ServiceAccount;
import com.atlas.identity.dto.ApiKeyResponse;
import com.atlas.identity.dto.CreateApiKeyRequest;
import com.atlas.identity.dto.CreateServiceAccountRequest;
import com.atlas.identity.dto.ServiceAccountResponse;
import com.atlas.identity.repository.ApiKeyRepository;
import com.atlas.identity.repository.ServiceAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ServiceAccountServiceIntegrationTest {

    @Autowired
    private ServiceAccountService serviceAccountService;

    @Autowired
    private ServiceAccountRepository serviceAccountRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        // Use the seeded ACME tenant
        tenantId = UUID.fromString("a0000000-0000-0000-0000-000000000010");
    }

    @Test
    void createServiceAccount_success() {
        String name = "test-sa-" + UUID.randomUUID();
        var request = new CreateServiceAccountRequest(tenantId, name);

        ServiceAccountResponse response = serviceAccountService.createServiceAccount(request);

        assertThat(response.serviceAccountId()).isNotNull();
        assertThat(response.tenantId()).isEqualTo(tenantId);
        assertThat(response.name()).isEqualTo(name);
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.createdAt()).isNotNull();

        // Verify in DB
        ServiceAccount saved = serviceAccountRepository.findById(response.serviceAccountId()).orElseThrow();
        assertThat(saved.getName()).isEqualTo(name);
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    void createServiceAccount_duplicateName_throws() {
        String name = "duplicate-sa-" + UUID.randomUUID();
        var request = new CreateServiceAccountRequest(tenantId, name);
        serviceAccountService.createServiceAccount(request);

        assertThatThrownBy(() -> serviceAccountService.createServiceAccount(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void generateApiKey_success_returnsRawKeyOnce() {
        // Create service account first
        String saName = "api-key-sa-" + UUID.randomUUID();
        ServiceAccountResponse sa = serviceAccountService.createServiceAccount(
                new CreateServiceAccountRequest(tenantId, saName));

        Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);
        var request = new CreateApiKeyRequest(sa.serviceAccountId(), expiresAt);

        ApiKeyResponse response = serviceAccountService.generateApiKey(request, tenantId);

        // Raw key is returned on creation
        assertThat(response.rawKey()).isNotNull();
        assertThat(response.rawKey()).startsWith("atl_");
        assertThat(response.apiKeyId()).isNotNull();
        assertThat(response.serviceAccountId()).isEqualTo(sa.serviceAccountId());
        assertThat(response.tenantId()).isEqualTo(tenantId);
        assertThat(response.keyPrefix()).isEqualTo(response.rawKey().substring(0, 8));
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.expiresAt()).isNotNull();

        // Verify hash is stored, not raw key
        ApiKey savedKey = apiKeyRepository.findById(response.apiKeyId()).orElseThrow();
        assertThat(savedKey.getKeyHash()).isNotEqualTo(response.rawKey());
        assertThat(savedKey.getKeyHash()).isNotBlank();

        // Verify hash matches by hashing raw key again
        String rehashed = serviceAccountService.hashKey(response.rawKey());
        assertThat(savedKey.getKeyHash()).isEqualTo(rehashed);

        // Verify lookup by hash works
        ApiKey foundByHash = apiKeyRepository.findByKeyHash(rehashed).orElseThrow();
        assertThat(foundByHash.getApiKeyId()).isEqualTo(response.apiKeyId());
    }

    @Test
    void generateApiKey_forDisabledAccount_throws() {
        String saName = "disabled-sa-" + UUID.randomUUID();
        ServiceAccountResponse sa = serviceAccountService.createServiceAccount(
                new CreateServiceAccountRequest(tenantId, saName));

        // Disable the service account
        ServiceAccount entity = serviceAccountRepository.findById(sa.serviceAccountId()).orElseThrow();
        entity.setStatus(ServiceAccount.Status.DISABLED);
        serviceAccountRepository.save(entity);

        var request = new CreateApiKeyRequest(sa.serviceAccountId(), null);

        assertThatThrownBy(() -> serviceAccountService.generateApiKey(request, tenantId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not active");
    }

    @Test
    void apiKey_isExpired_works() {
        String saName = "expired-sa-" + UUID.randomUUID();
        ServiceAccountResponse sa = serviceAccountService.createServiceAccount(
                new CreateServiceAccountRequest(tenantId, saName));

        // Create key that already expired
        Instant pastExpiry = Instant.now().minus(1, ChronoUnit.HOURS);
        var request = new CreateApiKeyRequest(sa.serviceAccountId(), pastExpiry);

        ApiKeyResponse response = serviceAccountService.generateApiKey(request, tenantId);

        ApiKey savedKey = apiKeyRepository.findById(response.apiKeyId()).orElseThrow();
        assertThat(savedKey.isExpired()).isTrue();
        assertThat(savedKey.isActive()).isFalse();
    }
}
