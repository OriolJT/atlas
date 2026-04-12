package com.atlas.identity.service;

import com.atlas.identity.domain.ApiKey;
import com.atlas.identity.domain.ServiceAccount;
import com.atlas.identity.dto.ApiKeyResponse;
import com.atlas.identity.dto.CreateApiKeyRequest;
import com.atlas.identity.dto.CreateServiceAccountRequest;
import com.atlas.identity.dto.ServiceAccountResponse;
import com.atlas.identity.repository.ApiKeyRepository;
import com.atlas.identity.repository.ServiceAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Service
public class ServiceAccountService {

    private static final String KEY_PREFIX = "atl_";
    private static final int RAW_KEY_BYTES = 32;

    private final ServiceAccountRepository serviceAccountRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public ServiceAccountService(ServiceAccountRepository serviceAccountRepository,
                                  ApiKeyRepository apiKeyRepository) {
        this.serviceAccountRepository = serviceAccountRepository;
        this.apiKeyRepository = apiKeyRepository;
    }

    @Transactional
    public ServiceAccountResponse createServiceAccount(CreateServiceAccountRequest request) {
        if (serviceAccountRepository.existsByTenantIdAndName(request.tenantId(), request.name())) {
            throw new IllegalArgumentException(
                    "Service account with name '" + request.name() + "' already exists for this tenant");
        }

        var serviceAccount = new ServiceAccount(request.tenantId(), request.name());
        serviceAccount = serviceAccountRepository.save(serviceAccount);
        return ServiceAccountResponse.from(serviceAccount);
    }

    @Transactional
    public ApiKeyResponse generateApiKey(CreateApiKeyRequest request, UUID callerTenantId) {
        ServiceAccount serviceAccount = serviceAccountRepository.findById(request.serviceAccountId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Service account not found: " + request.serviceAccountId()));

        if (!serviceAccount.getTenantId().equals(callerTenantId)) {
            throw new IllegalArgumentException(
                    "Service account not found: " + request.serviceAccountId());
        }

        if (!serviceAccount.isActive()) {
            throw new IllegalStateException("Service account is not active");
        }

        // Generate raw key: "atl_" + Base64URL random 32 bytes
        byte[] randomBytes = new byte[RAW_KEY_BYTES];
        secureRandom.nextBytes(randomBytes);
        String randomPart = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String rawKey = KEY_PREFIX + randomPart;

        // Store SHA-256 hash
        String keyHash = hashKey(rawKey);
        String keyPrefix = rawKey.substring(0, Math.min(8, rawKey.length()));

        var apiKey = new ApiKey(
                serviceAccount.getServiceAccountId(),
                serviceAccount.getTenantId(),
                keyHash,
                keyPrefix,
                request.expiresAt()
        );
        apiKey = apiKeyRepository.save(apiKey);

        // Return raw key ONCE (will not be retrievable again)
        return ApiKeyResponse.from(apiKey, rawKey);
    }

    String hashKey(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
