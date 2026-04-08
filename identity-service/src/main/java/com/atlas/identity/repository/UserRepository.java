package com.atlas.identity.repository;

import com.atlas.identity.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByTenantIdAndEmail(UUID tenantId, String email);

    Optional<User> findByEmail(String email);

    Optional<User> findByTenantIdAndUserId(UUID tenantId, UUID userId);

    boolean existsByTenantIdAndEmail(UUID tenantId, String email);
}
