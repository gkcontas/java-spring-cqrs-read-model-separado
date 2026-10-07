package com.gkcontas.cqrs.read.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gkcontas.cqrs.event.DomainEvent;
import com.gkcontas.cqrs.event.DomainEventRepository;
import com.gkcontas.cqrs.event.OrderEvents;
import com.gkcontas.cqrs.read.document.CustomerDashboard;
import com.gkcontas.cqrs.read.document.OrderView;
import com.gkcontas.cqrs.read.document.ProjectionCheckpoint;
import com.gkcontas.cqrs.read.repository.CustomerDashboardRepository;
import com.gkcontas.cqrs.read.repository.OrderViewRepository;
import com.gkcontas.cqrs.read.repository.ProjectionCheckpointRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the event stream in order and keeps the read model up to date.
 *
 * <p>Three properties make this safe to run repeatedly, and all three come from the
 * sequence rather than from the events themselves:
 *
 * <ul>
 *   <li><b>Ordered</b> — events are consumed by ascending sequence, so a status change
 *       never lands before the order it belongs to.
 *   <li><b>Idempotent</b> — the checkpoint advances only after a batch is applied, and
 *       each document records the sequence it reflects, so reapplying an event is a no-op
 *       instead of a double count.
 *   <li><b>Rebuildable</b> — resetting the checkpoint to zero reconstructs the entire read
 *       model from the write model, which is what makes it safe to change the shape of a
 *       document later.
 * </ul>
 *
 * <p>The checkpoint is advanced after the batch, not before: a crash in the middle means
 * the batch is replayed, and the per-document sequence check makes that harmless.
 */
@Service
public class OrderProjector {

    private static final Logger log = LoggerFactory.getLogger(OrderProjector.class);
    private static final int BATCH_SIZE = 200;

    private final DomainEventRepository events;
    private final OrderViewRepository orderViews;
    private final CustomerDashboardRepository dashboards;
    private final ProjectionCheckpointRepository checkpoints;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OrderProjector(DomainEventRepository events,
                          OrderViewRepository orderViews,
                          CustomerDashboardRepository dashboards,
                          ProjectionCheckpointRepository checkpoints,
                          ObjectMapper objectMapper,
                          Clock clock) {
        this.events = events;
        this.orderViews = orderViews;
        this.dashboards = dashboards;
        this.checkpoints = checkpoints;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Applies everything available and reports how many events it handled.
     *
     * @return the number of events applied in this run
     */
    @Transactional(readOnly = true)
    public int catchUp() {
        int applied = 0;
        List<DomainEvent> batch;

        while (!(batch = events.findBySequenceGreaterThanOrderBySequenceAsc(
                checkpoint(), Limit.of(BATCH_SIZE))).isEmpty()) {
            batch.forEach(this::apply);
            saveCheckpoint(batch.getLast().getSequence());
            applied += batch.size();
        }
        if (applied > 0) {
            log.debug("Projected {} event(s)", applied);
        }
        return applied;
    }

    private void apply(DomainEvent event) {
        switch (event.getType()) {
            case "OrderPlaced" -> applyPlaced(read(event, OrderEvents.OrderPlaced.class), event);
            case "OrderStatusChanged" ->
                    applyStatusChanged(read(event, OrderEvents.OrderStatusChanged.class), event);
            default -> log.warn("No projection for event type {}", event.getType());
        }
    }

    private void applyPlaced(OrderEvents.OrderPlaced placed, DomainEvent event) {
        long sequence = event.getSequence();

        if (alreadyApplied(orderViews.findById(placed.orderId())
                .map(OrderView::appliedSequence).orElse(0L), sequence)) {
            return;
        }

        orderViews.save(new OrderView(
                placed.orderId(), placed.customerId(), placed.customerName(),
                placed.customerEmail(), "PLACED", placed.total(),
                placed.items().stream()
                        .map(line -> new OrderView.Line(line.sku(), line.name(), line.category(),
                                line.quantity(), line.unitPrice(), line.lineTotal()))
                        .toList(),
                placed.placedAt(), placed.placedAt(), placed.version(), sequence));

        updateDashboard(placed, sequence);
    }

    private void updateDashboard(OrderEvents.OrderPlaced placed, long sequence) {
        CustomerDashboard current = dashboards.findById(placed.customerId()).orElse(null);
        if (current != null && alreadyApplied(current.appliedSequence(), sequence)) {
            // The totals here are maintained incrementally, so applying the same event a
            // second time would silently inflate them. This check is the only thing
            // standing between a replay and a wrong number on a dashboard.
            return;
        }

        Map<String, BigDecimal> byCategory = current == null
                ? new HashMap<>() : new HashMap<>(current.spentByCategory());
        placed.items().forEach(line -> byCategory.merge(line.category(), line.lineTotal(),
                BigDecimal::add));

        dashboards.save(new CustomerDashboard(
                placed.customerId(),
                placed.customerName(),
                placed.customerEmail(),
                current == null ? 1 : current.orderCount() + 1,
                current == null ? placed.total() : current.totalSpent().add(placed.total()),
                placed.placedAt(),
                byCategory,
                sequence));
    }

    private void applyStatusChanged(OrderEvents.OrderStatusChanged changed, DomainEvent event) {
        orderViews.findById(changed.orderId()).ifPresent(view -> {
            if (changed.version() <= view.version()) {
                // An event describing a state older than the one already projected. It
                // happens on replays and whenever two events race, and overwriting would
                // move the read model backwards.
                return;
            }
            orderViews.save(view.withStatus(changed.status(), changed.version(), changed.total(),
                    changed.changedAt(), event.getSequence()));
        });
    }

    private static boolean alreadyApplied(long appliedSequence, long sequence) {
        return appliedSequence >= sequence;
    }

    private <T> T read(DomainEvent event, Class<T> type) {
        try {
            return objectMapper.readValue(event.getPayload(), type);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read event " + event.getSequence(), e);
        }
    }

    public long checkpoint() {
        return checkpoints.findById(ProjectionCheckpoint.ORDERS)
                .map(ProjectionCheckpoint::lastSequence)
                .orElse(0L);
    }

    private void saveCheckpoint(long sequence) {
        checkpoints.save(new ProjectionCheckpoint(ProjectionCheckpoint.ORDERS, sequence,
                clock.instant()));
    }

    /**
     * Throws the read model away and builds it again from the event stream.
     *
     * <p>This is the capability that justifies much of the pattern. Changing the shape of a
     * document, fixing a bug in a projection or adding a new field is a deploy plus a
     * rebuild, instead of a migration over data that was only ever derived.
     */
    public RebuildResult rebuild() {
        long startedAt = System.nanoTime();
        orderViews.deleteAll();
        dashboards.deleteAll();
        checkpoints.deleteAll();

        int applied = catchUp();
        long millis = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("Rebuilt the read model from {} event(s) in {} ms", applied, millis);
        return new RebuildResult(applied, millis);
    }

    public record RebuildResult(int eventsApplied, long durationMillis) {
    }
}
