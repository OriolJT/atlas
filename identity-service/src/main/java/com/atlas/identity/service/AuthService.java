package com.atlas.identity.service;

import com.atlas.common.event.EventTypes;
import com.atlas.identity.domain.OutboxEvent;
import com.atlas.identity.domain.RefreshToken;
import com.atlas.identity.domain.Role;
import com.atlas.identity.domain.Tenant;
import com.atlas.identity.domain.User;
import com.atlas.identity.dto.LoginRequest;
import com.atlas.identity.dto.LoginResponse;
import com.atlas.identity.repository.OutboxRepository;
import com.atlas.identity.repository.RefreshTokenRepository;
import com.atlas.identity.repository.TenantRepository;
import com.atlas.identity.repository.UserRepository;
import com.atlas.identity.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final OutboxRepository outboxRepository;
    private final int maxFailedAttempts;
    private final int lockoutDurationMinutes;

    public AuthService(
            UserRepository userRepository,
            TenantRepository tenantRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            OutboxRepository outboxRepository,
            @Value("${atlas.security.max-failed-attempts}") int maxFailedAttempts,
            @Value("${atlas.security.lockout-duration-minutes}") int lockoutDurationMinutes) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.outboxRepository = outboxRepository;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockoutDurationMinutes = lockoutDurationMinutes;
    }

    /**
     * Dummy hash used for constant-time comparison when user is not found,
     * preventing timing side-channel attacks that reveal account existence.
     */
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$12$000000000000000000000uGWDREutBMhdOqDG7YkWf3fMFYGW2Kq6";

    @Transactional(noRollbackFor = AuthenticationException.class)
    public LoginResponse login(LoginRequest request) {
        var tenant = tenantRepository.findBySlug(request.tenantSlug())
                .orElseThrow(() -> {
                    passwordEncoder.matches(request.password(), DUMMY_PASSWORD_HASH);
                    return new AuthenticationException("ATLAS-AUTH-001", "Invalid credentials");
                });

        if (tenant.getStatus() == Tenant.Status.SUSPENDED) {
            passwordEncoder.matches(request.password(), DUMMY_PASSWORD_HASH);
            throw new AuthenticationException("ATLAS-AUTH-006", "Tenant is suspended");
        }

        var optionalUser = userRepository.findByTenantIdAndEmailWithRoles(tenant.getTenantId(), request.email());

        if (optionalUser.isEmpty()) {
            // Perform a dummy password check to prevent timing side-channel
            passwordEncoder.matches(request.password(), DUMMY_PASSWORD_HASH);
            throw new AuthenticationException("ATLAS-AUTH-001", "Invalid credentials");
        }

        User user = optionalUser.get();

        if (user.getStatus() == User.Status.DISABLED) {
            throw new AuthenticationException("ATLAS-AUTH-007", "Account is disabled");
        }

        if (user.isLocked()) {
            throw new AuthenticationException("ATLAS-AUTH-002", "Account is locked. Try again later.");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            user.incrementFailedLoginAttempts(maxFailedAttempts, lockoutDurationMinutes);
            userRepository.save(user);
            throw new AuthenticationException("ATLAS-AUTH-001", "Invalid credentials");
        }

        user.resetFailedLoginAttempts();
        userRepository.save(user);

        return issueTokenPair(user);
    }

    @Transactional
    public LoginResponse refresh(String rawRefreshToken) {
        String tokenHash = jwtTokenProvider.hashRefreshToken(rawRefreshToken);
        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new AuthenticationException("ATLAS-AUTH-001", "Invalid refresh token"));

        if (storedToken.isRevoked()) {
            // Revoke all tokens for this user to prevent reuse of stolen token families
            refreshTokenRepository.revokeAllByUserId(storedToken.getUserId());
            throw new AuthenticationException("ATLAS-AUTH-005", "Refresh token has been revoked");
        }

        if (storedToken.isExpired()) {
            throw new AuthenticationException("ATLAS-AUTH-003", "Refresh token has expired");
        }

        // Rotate: revoke old token
        storedToken.revoke();
        refreshTokenRepository.save(storedToken);

        User user = userRepository.findById(storedToken.getUserId())
                .orElseThrow(() -> new AuthenticationException("ATLAS-AUTH-001", "User not found"));

        return issueTokenPair(user);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        String tokenHash = jwtTokenProvider.hashRefreshToken(rawRefreshToken);
        refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
            token.revoke();
            refreshTokenRepository.save(token);

            Map<String, Object> payload = Map.of(
                    "tokenId", token.getTokenId().toString(),
                    "userId", token.getUserId().toString(),
                    "tenantId", token.getTenantId().toString()
            );

            outboxRepository.save(new OutboxEvent(
                    "RefreshToken", token.getTokenId(), "token.revoked",
                    EventTypes.TOPIC_DOMAIN_EVENTS, payload, token.getTenantId()));
        });
    }

    private LoginResponse issueTokenPair(User user) {
        List<String> roles = user.getRoles().stream()
                .map(Role::getName)
                .toList();

        String accessToken = jwtTokenProvider.generateAccessToken(user.getUserId(), user.getTenantId(), roles);
        String rawRefreshToken = jwtTokenProvider.generateRefreshTokenValue();
        String refreshTokenHash = jwtTokenProvider.hashRefreshToken(rawRefreshToken);

        Instant expiresAt = Instant.now().plus(jwtTokenProvider.getRefreshTokenExpiryDays(), ChronoUnit.DAYS);
        var refreshToken = new RefreshToken(refreshTokenHash, user.getUserId(), user.getTenantId(), expiresAt);
        refreshTokenRepository.save(refreshToken);

        return new LoginResponse(accessToken, rawRefreshToken, user.getUserId(), user.getTenantId());
    }

    public static class AuthenticationException extends RuntimeException {
        private final String errorCode;

        public AuthenticationException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }
}
