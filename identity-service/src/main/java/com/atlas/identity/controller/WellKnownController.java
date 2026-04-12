package com.atlas.identity.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class WellKnownController {

    private final String baseUrl;

    public WellKnownController(
            @Value("${atlas.identity.base-url:http://localhost:8081}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @GetMapping("/.well-known/openid-configuration")
    public Map<String, Object> openIdConfiguration() {
        return Map.of(
            "issuer", baseUrl,
            "authorization_endpoint", baseUrl + "/api/v1/auth/login",
            "token_endpoint", baseUrl + "/api/v1/auth/login",
            "response_types_supported", List.of("code"),
            "subject_types_supported", List.of("public"),
            "id_token_signing_alg_values_supported", List.of("HS256")
        );
    }
}
