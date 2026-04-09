package com.atlas.identity.controller;

import com.atlas.identity.dto.ApiKeyResponse;
import com.atlas.identity.dto.CreateApiKeyRequest;
import com.atlas.identity.dto.CreateServiceAccountRequest;
import com.atlas.identity.dto.ServiceAccountResponse;
import com.atlas.identity.service.ServiceAccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1")
public class ServiceAccountController {

    private final ServiceAccountService serviceAccountService;

    public ServiceAccountController(ServiceAccountService serviceAccountService) {
        this.serviceAccountService = serviceAccountService;
    }

    @PostMapping("/service-accounts")
    public ResponseEntity<ServiceAccountResponse> createServiceAccount(
            @Valid @RequestBody CreateServiceAccountRequest request) {
        ServiceAccountResponse response = serviceAccountService.createServiceAccount(request);
        URI location = URI.create("/api/v1/service-accounts/" + response.serviceAccountId());
        return ResponseEntity.created(location).body(response);
    }

    @PostMapping("/api-keys")
    public ResponseEntity<ApiKeyResponse> generateApiKey(
            @Valid @RequestBody CreateApiKeyRequest request) {
        ApiKeyResponse response = serviceAccountService.generateApiKey(request);
        URI location = URI.create("/api/v1/api-keys/" + response.apiKeyId());
        return ResponseEntity.created(location).body(response);
    }
}
