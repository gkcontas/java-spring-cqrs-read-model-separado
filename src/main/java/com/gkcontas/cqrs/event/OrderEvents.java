package com.gkcontas.cqrs.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the write model announces.
 *
 * <p>Each event carries everything the projections need, denormalised already: the
 * customer's name, the product's category. The projector then never queries the write
 * model, which is what keeps the two sides genuinely independent — and what makes it
 * possible to move the read side to another machine, or another team, later.
 *
 * <p>The {@code version} travels too, so a projection can reject an event describing an
 * older state than the one it already holds.
 */
public final class OrderEvents {

    private OrderEvents() {
    }

    public record OrderLine(String sku, String name, String category, int quantity,
                            BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    public record OrderPlaced(
            UUID orderId,
            long version,
            UUID customerId,
            String customerName,
            String customerEmail,
            BigDecimal total,
            List<OrderLine> items,
            Instant placedAt) {
    }

    public record OrderStatusChanged(
            UUID orderId,
            long version,
            UUID customerId,
            String status,
            BigDecimal total,
            Instant changedAt) {
    }
}
