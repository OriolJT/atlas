package com.atlas.identity.service;

import com.atlas.identity.domain.Tenant;
import com.atlas.identity.dto.CreateTenantRequest;
import com.atlas.identity.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class TenantService {

    private final TenantRepository tenantRepository;

    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public Tenant createTenant(CreateTenantRequest request) {
        if (tenantRepository.existsBySlug(request.slug())) {
            throw new IllegalArgumentException("Tenant with slug '" + request.slug() + "' already exists");
        }
        var tenant = new Tenant(request.name(), request.slug());
        return tenantRepository.save(tenant);
    }

    @Transactional(readOnly = true)
    public Optional<Tenant> findById(UUID id) {
        return tenantRepository.findById(id);
    }
}
