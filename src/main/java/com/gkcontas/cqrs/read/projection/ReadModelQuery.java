package com.gkcontas.cqrs.read.projection;

import com.gkcontas.cqrs.read.document.CustomerDashboard;
import com.gkcontas.cqrs.read.document.OrderView;
import com.gkcontas.cqrs.read.repository.CustomerDashboardRepository;
import com.gkcontas.cqrs.read.repository.OrderViewRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The query side, including the answer to "I just wrote this and I cannot see it".
 *
 * <p>Read-your-own-writes is the complaint CQRS produces on its first day in production,
 * and the usual replies are unsatisfying: tell the user to refresh, or read from the write
 * model and give up half the benefit.
 *
 * <p>The approach here is the third one. The command returns the sequence it wrote, and a
 * read may quote it: the query then waits for the projector to pass that position before
 * answering. The wait is bounded and usually invisible — the projector is normally
 * milliseconds behind — and it keeps the guarantee exactly where it is needed instead of
 * degrading every read in the system.
 */
@Service
public class ReadModelQuery {

    private static final Duration POLL = Duration.ofMillis(20);

    private final OrderViewRepository orderViews;
    private final CustomerDashboardRepository dashboards;
    private final OrderProjector projector;

    public ReadModelQuery(OrderViewRepository orderViews, CustomerDashboardRepository dashboards,
                          OrderProjector projector) {
        this.orderViews = orderViews;
        this.dashboards = dashboards;
        this.projector = projector;
    }

    public Optional<OrderView> order(UUID orderId) {
        return orderViews.findById(orderId);
    }

    public Optional<CustomerDashboard> dashboard(UUID customerId) {
        return dashboards.findById(customerId);
    }

    /**
     * Waits until the read model reflects at least the given sequence.
     *
     * @return true when the read model caught up within the timeout
     */
    public boolean awaitSequence(long sequence, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (projector.checkpoint() < sequence) {
            if (System.nanoTime() > deadline) {
                // Timing out is reported rather than hidden: the caller gets to decide
                // between a stale answer and an error, and both are better than a wait
                // that never ends.
                return false;
            }
            sleep();
        }
        return true;
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the read model", e);
        }
    }
}
