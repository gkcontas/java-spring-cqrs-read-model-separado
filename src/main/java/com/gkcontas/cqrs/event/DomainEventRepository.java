package com.gkcontas.cqrs.event;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainEventRepository extends JpaRepository<DomainEvent, Long> {

    List<DomainEvent> findBySequenceGreaterThanOrderBySequenceAsc(long sequence, Limit limit);

    /** The head of the stream, used to report how far behind the projector is. */
    DomainEvent findTopByOrderBySequenceDesc();
}
