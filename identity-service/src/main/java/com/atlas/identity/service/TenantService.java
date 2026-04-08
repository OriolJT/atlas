package com.atlas.identity.service;

import com.atlas.identity.domain.OutboxEvent;
import com.atlas.identity.domain.Tenant;
import com.atlas.identity.dto.CreateTenantRequest;
import com.atlas.identity.repository.OutboxRepository;
import com.atlas.identity.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class TenantService {

    private final TenantRepository tenantRepository;
    private final OutboxRepository outboxRepository;

    public TenantService(TenantRepository tenantRepository, OutboxRepository outboxRepository) {
        this.tenantRepository = tenantRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public Tenant createTenant(CreateTenantRequest request) {
        if (tenantRepository.existsBySlug(request.slug())) {
            throw new IllegalArgumentException("Tenant with slug '" + request.slug() + "' already exists");
        }
        var tenant = new Tenant(request.name(), request.slug());
        tenant = tenantRepository.save(tenant);

        Map<String, Object> payload = Map.of(
                "tenantId", tenant.getTenantId().toString(),
                "name", tenant.getName(),
                "slug", tenant.getSlug(),
                "status", tenant.getStatus().name()
        );

        outboxRepository.save(new OutboxEvent(
                "Tenant", tenant.getTenantId(), "tenant.created",
                "domain.events", payload, tenant.getTenantId()));
        outboxRepository.save(new OutboxEvent(
                "Tenant", tenant.getTenantId(), "tenant.created",
                "audit.events", payload, tenant.getTenantId()));

        return tenant;
    }

    @Transactional(readOnly = true)
    public Optional<Tenant> findById(UUID id) {
        return tenantRepository.findById(id);
    }
}
