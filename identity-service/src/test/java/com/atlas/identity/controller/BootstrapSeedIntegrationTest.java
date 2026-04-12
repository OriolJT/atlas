package com.atlas.identity.controller;

import com.atlas.identity.TestcontainersConfiguration;
import com.atlas.identity.dto.LoginRequest;
import com.atlas.identity.dto.LoginResponse;
import com.atlas.identity.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class BootstrapSeedIntegrationTest {

    private static final UUID ACME_TENANT_ID = UUID.fromString("a0000000-0000-0000-0000-000000000010");
    private static final UUID ADMIN_USER_ID = UUID.fromString("a0000000-0000-0000-0000-000000000020");
    private static final String ADMIN_EMAIL = "admin@acme.com";
    private static final String ADMIN_PASSWORD = "Atlas2026!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void loginAsAdmin_withSeedCredentials_returnsValidJwt() {
        var loginRequest = new LoginRequest("acme-corp", ADMIN_EMAIL, ADMIN_PASSWORD);

        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/login", loginRequest, LoginResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        LoginResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.accessToken()).isNotBlank();
        assertThat(body.refreshToken()).isNotBlank();
        assertThat(body.userId()).isEqualTo(ADMIN_USER_ID);
        assertThat(body.tenantId()).isEqualTo(ACME_TENANT_ID);
    }

    @Test
    void loginAsAdmin_jwtContainsCorrectClaims() {
        var loginRequest = new LoginRequest("acme-corp", ADMIN_EMAIL, ADMIN_PASSWORD);

        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/login", loginRequest, LoginResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String accessToken = response.getBody().accessToken();

        Claims claims = jwtTokenProvider.parseAccessToken(accessToken);
        assertThat(claims.getSubject()).isEqualTo(ADMIN_USER_ID.toString());
        assertThat(claims.get("tenant_id", String.class)).isEqualTo(ACME_TENANT_ID.toString());

        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);
        assertThat(roles).contains("TENANT_ADMIN");
    }

    @Test
    void loginAsAdmin_wrongPassword_returns401() {
        var loginRequest = new LoginRequest("acme-corp", ADMIN_EMAIL, "WrongPassword!");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/login", loginRequest, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("ATLAS-AUTH-001");
    }
}
