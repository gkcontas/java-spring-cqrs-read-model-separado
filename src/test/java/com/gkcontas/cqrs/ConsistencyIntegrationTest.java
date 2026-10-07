package com.gkcontas.cqrs;

import com.gkcontas.cqrs.read.projection.ReadModelQuery;
import com.gkcontas.cqrs.write.model.Customer;
import com.gkcontas.cqrs.write.model.Product;
import com.gkcontas.cqrs.write.service.OrderCommandService;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The complaint CQRS produces on its first day in production.
 *
 * <p>The user saves something and immediately looks for it, and it is not there. The
 * window is normally milliseconds, which is exactly what makes it hard to reproduce — and
 * why the projector is held still here instead of being waited for.
 */
class ConsistencyIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ReadModelQuery queries;

    @Test
    void theReadModelIsBehindUntilTheProjectorRuns() {
        Customer ana = customer("Ana");
        Product keyboard = product("KB-001", "peripherals", "300.00");

        OrderCommandService.CommandResult placed = place(ana, keyboard, 1);

        // Committed in the write model, invisible in the read model. Nothing is broken:
        // this is the state the pattern buys its performance with.
        assertThat(orders.findById(placed.orderId())).isPresent();
        assertThat(queries.order(placed.orderId())).isEmpty();

        projector.catchUp();
        assertThat(queries.order(placed.orderId())).isPresent();
    }

    @Test
    void waitingForTheSequenceReturnsTheWriteThatWasJustMade() {
        Customer ana = customer("Ana");
        Product keyboard = product("KB-002", "peripherals", "120.00");
        OrderCommandService.CommandResult placed = place(ana, keyboard, 2);

        // The projector runs a moment later, as it would in production.
        CompletableFuture.runAsync(() -> {
            sleep();
            projector.catchUp();
        });

        boolean caughtUp = queries.awaitSequence(placed.sequence(), Duration.ofSeconds(5));

        assertThat(caughtUp).isTrue();
        assertThat(queries.order(placed.orderId())).isPresent();
    }

    @Test
    void reportsFailureWhenTheReadModelNeverCatchesUp() {
        Customer ana = customer("Ana");
        Product keyboard = product("KB-003", "peripherals", "90.00");
        OrderCommandService.CommandResult placed = place(ana, keyboard, 1);

        // Nothing advances the projector here. Timing out is reported rather than hidden:
        // the caller gets to choose between a stale answer and an error, and a wait that
        // never ends is worse than both.
        assertThat(queries.awaitSequence(placed.sequence(), Duration.ofMillis(300))).isFalse();
    }

    @Test
    void waitingIsFreeWhenTheReadModelIsAlreadyAhead() {
        Customer ana = customer("Ana");
        Product keyboard = product("KB-004", "peripherals", "70.00");
        OrderCommandService.CommandResult placed = place(ana, keyboard, 1);
        projector.catchUp();

        long startedAt = System.nanoTime();
        boolean caughtUp = queries.awaitSequence(placed.sequence(), Duration.ofSeconds(5));

        // The usual case: the guarantee costs nothing when the projector is already past
        // the requested position, which is why it can be asked for on every write path
        // that needs it without slowing the rest of the system down.
        assertThat(caughtUp).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofMillis(100));
    }

    private static void sleep() {
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
