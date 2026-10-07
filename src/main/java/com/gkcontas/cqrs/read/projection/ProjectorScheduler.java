package com.gkcontas.cqrs.read.projection;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The timer, kept apart from the projector.
 *
 * <p>Two reasons, and the second is not cosmetic. A test needs to call {@code catchUp()} at
 * the exact moment it chooses rather than wait for a tick; and a scheduled method calling a
 * transactional one on the same object would bypass the proxy, so the transaction would
 * never start.
 */
@Component
@ConditionalOnProperty(name = "app.projection.enabled", havingValue = "true", matchIfMissing = true)
class ProjectorScheduler {

    private final OrderProjector projector;

    ProjectorScheduler(OrderProjector projector) {
        this.projector = projector;
    }

    @Scheduled(fixedDelayString = "${app.projection.interval:PT0.2S}")
    void project() {
        projector.catchUp();
    }
}
