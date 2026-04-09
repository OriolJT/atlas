package com.atlas.identity.repository;

import com.atlas.identity.domain.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query("SELECT e FROM OutboxEvent e WHERE e.publishedAt IS NULL ORDER BY e.aggregateId, e.createdAt ASC")
    List<OutboxEvent> findUnpublishedOrderedByAggregateAndCreatedAt();

    @Query("SELECT e FROM OutboxEvent e WHERE e.publishedAt IS NULL ORDER BY e.aggregateId, e.createdAt ASC")
    List<OutboxEvent> findUnpublishedBatch(Pageable pageable);

    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.publishedAt IS NOT NULL AND e.publishedAt < :cutoff")
    void deletePublishedBefore(@Param("cutoff") LocalDateTime cutoff);
}
