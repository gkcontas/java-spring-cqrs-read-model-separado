package com.gkcontas.cqrs.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the event in the same transaction as the change it describes.
 *
 * <p>{@code Propagation.MANDATORY} states that as a constraint instead of a convention:
 * the method refuses to run without a transaction already open. Recording an event outside
 * one would commit a fact about a change that may yet roll back, and the read model would
 * end up showing an order the write model never kept.
 */
@Component
public class EventRecorder {

    private final DomainEventRepository events;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EventRecorder(DomainEventRepository events, ObjectMapper objectMapper, Clock clock) {
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public DomainEvent record(UUID aggregateId, Object event) {
        return events.save(new DomainEvent(null, UUID.randomUUID(),
                event.getClass().getSimpleName(), aggregateId, serialize(event), clock.instant()));
    }

    private String serialize(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise " + event.getClass(), e);
        }
    }
}
