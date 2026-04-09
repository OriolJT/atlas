// Service-local copy — intentionally not in common module to keep services independently deployable
package com.atlas.identity.config;

import com.atlas.identity.security.TenantContext;
import jakarta.persistence.EntityManager;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.springframework.beans.factory.support.ScopeNotActiveException;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class TenantFilterAspect {

    private final EntityManager entityManager;
    private final TenantContext tenantContext;

    public TenantFilterAspect(EntityManager entityManager, TenantContext tenantContext) {
        this.entityManager = entityManager;
        this.tenantContext = tenantContext;
    }

    @Before("execution(* com.atlas.identity.repository.*.*(..))")
    public void enableTenantFilter() {
        try {
            if (tenantContext.isSet()) {
                Session session = entityManager.unwrap(Session.class);
                session.enableFilter("tenantFilter").setParameter("tenantId", tenantContext.getTenantId());
            }
        } catch (ScopeNotActiveException e) {
            // No active request scope (e.g. scheduled tasks) — skip tenant filter
        }
    }
}
