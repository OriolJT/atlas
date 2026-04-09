package com.atlas.identity.repository;

import com.atlas.identity.domain.ServiceAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ServiceAccountRepository extends JpaRepository<ServiceAccount, UUID> {

    List<ServiceAccount> findByTenantId(UUID tenantId);

    boolean existsByTenantIdAndName(UUID tenantId, String name);
}
