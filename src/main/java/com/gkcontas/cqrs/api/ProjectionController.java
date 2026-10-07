package com.gkcontas.cqrs.api;

import com.gkcontas.cqrs.event.DomainEvent;
import com.gkcontas.cqrs.event.DomainEventRepository;
import com.gkcontas.cqrs.read.projection.OrderProjector;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The lag, made visible.
 *
 * <p>Eventual consistency without instrumentation is just unpredictable consistency. The
 * number that matters is not "is it eventually consistent" — it always is — but "how far
 * behind is it right now", and that has to be a metric somebody can alert on.
 */
@RestController
@RequestMapping("/projections")
class ProjectionController {

    private final OrderProjector projector;
    private final DomainEventRepository events;
    private final Clock clock;

    ProjectionController(OrderProjector projector, DomainEventRepository events, Clock clock) {
        this.projector = projector;
        this.events = events;
        this.clock = clock;
    }

    record ProjectionStatus(long writeSequence, long readCheckpoint, long lagEvents,
                            Long lagMillis) {
    }

    @GetMapping("/status")
    ProjectionStatus status() {
        DomainEvent head = events.findTopByOrderBySequenceDesc();
        long writeSequence = head == null ? 0 : head.getSequence();
        long checkpoint = projector.checkpoint();

        // The age of the oldest unapplied event is the honest measure of lag. A count of
        // pending events says nothing about how stale the data a user is looking at is.
        Long lagMillis = (head == null || checkpoint >= writeSequence)
                ? 0L
                : Duration.between(oldestPending(checkpoint), clock.instant()).toMillis();

        return new ProjectionStatus(writeSequence, checkpoint,
                Math.max(0, writeSequence - checkpoint), lagMillis);
    }

    private Instant oldestPending(long checkpoint) {
        return events.findBySequenceGreaterThanOrderBySequenceAsc(checkpoint,
                        org.springframework.data.domain.Limit.of(1))
                .stream().findFirst()
                .map(DomainEvent::getOccurredAt)
                .orElse(clock.instant());
    }

    @PostMapping("/catch-up")
    Map<String, Integer> catchUp() {
        return Map.of("applied", projector.catchUp());
    }

    /** Throws the read model away and builds it again from the event stream. */
    @PostMapping("/rebuild")
    OrderProjector.RebuildResult rebuild() {
        return projector.rebuild();
    }
}
