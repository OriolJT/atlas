package com.atlas.identity.controller;

import com.atlas.identity.dto.ApiKeyResponse;
import com.atlas.identity.dto.CreateApiKeyRequest;
import com.atlas.identity.dto.CreateServiceAccountRequest;
import com.atlas.identity.dto.ServiceAccountResponse;
import com.atlas.identity.service.ServiceAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Tag(name = "Service Accounts", description = "Machine-to-machine service account and API key management")
@RestController
@RequestMapping("/api/v1")
public class ServiceAccountController {

    private final ServiceAccountService serviceAccountService;

    public ServiceAccountController(ServiceAccountService serviceAccountService) {
        this.serviceAccountService = serviceAccountService;
    }

    @Operation(summary = "Create service account", description = "Create a new service account for machine-to-machine authentication")
    @ApiResponse(responseCode = "201", description = "Service account created")
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @PostMapping("/service-accounts")
    public ResponseEntity<ServiceAccountResponse> createServiceAccount(
            @Valid @RequestBody CreateServiceAccountRequest request) {
        ServiceAccountResponse response = serviceAccountService.createServiceAccount(request);
        URI location = URI.create("/api/v1/service-accounts/" + response.serviceAccountId());
        return ResponseEntity.created(location).body(response);
    }

    @Operation(summary = "Generate API key", description = "Generate a new API key for a service account")
    @ApiResponse(responseCode = "201", description = "API key generated")
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "404", description = "Service account not found")
    @PostMapping("/api-keys")
    public ResponseEntity<ApiKeyResponse> generateApiKey(
            @Valid @RequestBody CreateApiKeyRequest request) {
        ApiKeyResponse response = serviceAccountService.generateApiKey(request);
        URI location = URI.create("/api/v1/api-keys/" + response.apiKeyId());
        return ResponseEntity.created(location).body(response);
    }
}
