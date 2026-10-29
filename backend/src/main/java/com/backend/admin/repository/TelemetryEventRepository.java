package com.backend.admin.repository;

import com.backend.admin.entity.TelemetryEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface TelemetryEventRepository extends JpaRepository<TelemetryEvent, Long> {

    long countByEventType(String eventType);

    long countByEventTypeAndCreatedAtAfter(String eventType, Instant after);

    @Query("SELECT COALESCE(SUM(t.payloadBytes), 0) FROM TelemetryEvent t")
    long sumPayloadBytes();

    @Query("SELECT t.ttlSeconds, COUNT(t) FROM TelemetryEvent t WHERE t.ttlSeconds IS NOT NULL GROUP BY t.ttlSeconds")
    List<Object[]> countByTtlSeconds();

    List<TelemetryEvent> findTop50ByOrderByCreatedAtDesc();
}
