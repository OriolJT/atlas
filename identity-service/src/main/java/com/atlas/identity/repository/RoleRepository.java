package com.atlas.identity.repository;

import com.atlas.identity.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByTenantIdAndName(UUID tenantId, String name);

    boolean existsByTenantIdAndName(UUID tenantId, String name);

    @Query("SELECT r FROM Role r LEFT JOIN FETCH r.permissions WHERE r.tenantId = :tenantId")
    List<Role> findByTenantIdWithPermissions(@Param("tenantId") UUID tenantId);
}
