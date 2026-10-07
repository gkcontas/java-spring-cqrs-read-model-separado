package com.gkcontas.cqrs.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The channel between the two models, and the reason the read side can be rebuilt.
 *
 * <p>The sequence is a database-generated {@code BIGSERIAL}, which gives three things at
 * once: a total order for the projector to consume in, a position it can checkpoint at,
 * and a number the client can quote when it wants to read its own write. A timestamp gives
 * none of them — two events in the same millisecond have no order, and clocks move
 * backwards.
 */
@Entity
@Table(name = "domain_event")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class DomainEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long sequence;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(nullable = false, length = 60)
    private String type;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
