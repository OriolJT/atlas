package com.atlas.workflow.repository;

import com.atlas.workflow.domain.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query("SELECT o FROM OutboxEvent o WHERE o.publishedAt IS NULL ORDER BY o.createdAt")
    List<OutboxEvent> findUnpublished();

    @Query("SELECT o FROM OutboxEvent o WHERE o.publishedAt IS NULL ORDER BY o.createdAt")
    List<OutboxEvent> findUnpublishedBatch(Pageable pageable);
}
