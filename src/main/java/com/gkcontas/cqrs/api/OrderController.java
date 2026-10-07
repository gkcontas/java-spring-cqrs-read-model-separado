package com.gkcontas.cqrs.api;

import com.gkcontas.cqrs.read.document.OrderView;
import com.gkcontas.cqrs.read.projection.ReadModelQuery;
import com.gkcontas.cqrs.write.model.OrderStatus;
import com.gkcontas.cqrs.write.service.OrderCommandService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Commands and queries, deliberately asymmetric.
 *
 * <p>A command answers with an identifier and a sequence; it does not answer with the
 * thing it created, because the thing it created lives in a model that has not been
 * updated yet. A query answers with a document and never touches the write model.
 *
 * <p>Returning the sequence is what makes the asymmetry workable for the caller: it is the
 * receipt that lets the next read ask for a view that already includes this change.
 */
@RestController
@RequestMapping("/orders")
class OrderController {

    private static final Duration CONSISTENT_READ_TIMEOUT = Duration.ofSeconds(5);

    private final OrderCommandService commands;
    private final ReadModelQuery queries;

    OrderController(OrderCommandService commands, ReadModelQuery queries) {
        this.commands = commands;
        this.queries = queries;
    }

    record PlaceOrderRequest(
            @NotNull(message = "must not be null") UUID customerId,
            @NotEmpty(message = "must not be empty") Map<UUID, Integer> items) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    OrderCommandService.CommandResult place(@Valid @RequestBody PlaceOrderRequest request) {
        return commands.placeOrder(request.customerId(), request.items());
    }

    @PostMapping("/{id}/pay")
    OrderCommandService.CommandResult pay(@PathVariable UUID id) {
        return commands.changeStatus(id, OrderStatus.PAID);
    }

    @PostMapping("/{id}/cancel")
    OrderCommandService.CommandResult cancel(@PathVariable UUID id) {
        return commands.changeStatus(id, OrderStatus.CANCELLED);
    }

    /**
     * Reads the projection.
     *
     * @param minSequence when given, the read waits for the projection to reach it, which
     *                    is how a caller sees its own write without giving up the read
     *                    model for everybody else
     */
    @GetMapping("/{id}")
    OrderView byId(@PathVariable UUID id,
                   @RequestParam(required = false) Long minSequence) {
        if (minSequence != null && !queries.awaitSequence(minSequence, CONSISTENT_READ_TIMEOUT)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The read model did not catch up with sequence " + minSequence);
        }
        return queries.order(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No order view for " + id + " — it may not have been projected yet"));
    }
}
