package com.atlas.identity.service;

import com.atlas.common.event.EventTypes;
import com.atlas.identity.domain.OutboxEvent;
import com.atlas.identity.domain.User;
import com.atlas.identity.dto.CreateUserRequest;
import com.atlas.identity.repository.OutboxRepository;
import com.atlas.identity.repository.TenantRepository;
import com.atlas.identity.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final OutboxRepository outboxRepository;

    public UserService(UserRepository userRepository, TenantRepository tenantRepository,
                       PasswordEncoder passwordEncoder, OutboxRepository outboxRepository) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public User createUser(CreateUserRequest request) {
        if (!tenantRepository.existsById(request.tenantId())) {
            throw new IllegalArgumentException("Tenant with id '" + request.tenantId() + "' does not exist");
        }
        if (userRepository.existsByTenantIdAndEmail(request.tenantId(), request.email())) {
            throw new IllegalArgumentException(
                    "User with email '" + request.email() + "' already exists in this tenant");
        }
        String passwordHash = passwordEncoder.encode(request.password());
        var user = new User(request.tenantId(), request.email(), passwordHash, request.firstName(), request.lastName());
        user = userRepository.save(user);

        Map<String, Object> payload = Map.of(
                "userId", user.getUserId().toString(),
                "tenantId", user.getTenantId().toString(),
                "email", user.getEmail(),
                "firstName", user.getFirstName(),
                "lastName", user.getLastName()
        );

        outboxRepository.save(new OutboxEvent(
                "User", user.getUserId(), "user.created",
                EventTypes.TOPIC_DOMAIN_EVENTS, payload, user.getTenantId()));
        outboxRepository.save(new OutboxEvent(
                "User", user.getUserId(), "user.created",
                EventTypes.TOPIC_AUDIT_EVENTS, payload, user.getTenantId()));

        return user;
    }

    @Transactional(readOnly = true)
    public Optional<User> findById(UUID userId) {
        return userRepository.findById(userId);
    }

    @Transactional(readOnly = true)
    public Optional<User> findByTenantIdAndUserId(UUID tenantId, UUID userId) {
        return userRepository.findByTenantIdAndUserId(tenantId, userId);
    }
}
