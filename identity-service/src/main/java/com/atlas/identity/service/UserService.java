package com.atlas.identity.service;

import com.atlas.identity.domain.User;
import com.atlas.identity.dto.CreateUserRequest;
import com.atlas.identity.repository.TenantRepository;
import com.atlas.identity.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, TenantRepository tenantRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
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
        return userRepository.save(user);
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
