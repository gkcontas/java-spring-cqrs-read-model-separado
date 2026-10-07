package com.gkcontas.cqrs.read.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The order summary, as one document.
 *
 * <p>Everything the screen shows is here, already joined: the customer's name, each item's
 * product name and category, the line totals. Rendering it is a lookup by key — no joins,
 * no N+1, and no chance of the query getting slower because the schema grew.
 *
 * <p>The duplication is the point, not an oversight. The customer's name appears in every
 * order she ever placed, and if she changes it the old documents keep the old name until a
 * projection updates them. Deciding whether that is acceptable is a product question, and
 * CQRS forces it to be asked out loud.
 *
 * @param appliedSequence the position in the event stream this document reflects, which is
 *                        what makes a replay idempotent and an out-of-order event harmless
 */
@Document("order_views")
public record OrderView(
        @Id UUID orderId,
        UUID customerId,
        String customerName,
        String customerEmail,
        String status,
        BigDecimal total,
        List<Line> items,
        Instant placedAt,
        Instant updatedAt,
        long version,
        long appliedSequence) {

    public record Line(String sku, String name, String category, int quantity,
                       BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    public OrderView withStatus(String newStatus, long newVersion, BigDecimal newTotal,
                                Instant changedAt, long sequence) {
        return new OrderView(orderId, customerId, customerName, customerEmail, newStatus,
                newTotal, items, placedAt, changedAt, newVersion, sequence);
    }
}
