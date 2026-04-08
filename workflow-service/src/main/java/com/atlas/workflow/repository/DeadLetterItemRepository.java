package com.atlas.workflow.repository;

import com.atlas.workflow.domain.DeadLetterItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeadLetterItemRepository extends JpaRepository<DeadLetterItem, UUID> {

    List<DeadLetterItem> findByTenantIdAndReplayedFalse(UUID tenantId);

    Optional<DeadLetterItem> findByDeadLetterIdAndTenantId(UUID deadLetterId, UUID tenantId);
}
