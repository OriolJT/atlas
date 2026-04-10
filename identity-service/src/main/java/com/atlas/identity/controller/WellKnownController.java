package com.atlas.identity.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class WellKnownController {

    @GetMapping("/.well-known/openid-configuration")
    public Map<String, Object> openIdConfiguration() {
        return Map.of(
            "issuer", "http://localhost:8081",
            "authorization_endpoint", "http://localhost:8081/api/v1/auth/login",
            "token_endpoint", "http://localhost:8081/api/v1/auth/login",
            "jwks_uri", "http://localhost:8081/.well-known/jwks.json",
            "response_types_supported", List.of("code"),
            "subject_types_supported", List.of("public"),
            "id_token_signing_alg_values_supported", List.of("HS256")
        );
    }
}
