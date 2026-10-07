package com.gkcontas.cqrs.read.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The pre-aggregated dashboard.
 *
 * <p>In the write model these numbers come from a group by over the customer's entire
 * history, and the query gets slower every time she buys something. Here they are stored,
 * and reading them costs the same on the first order as on the ten-thousandth.
 *
 * <p>The price is that the numbers are maintained incrementally, so every event has to be
 * applied exactly once. That is what {@code appliedSequence} is for: an event already
 * reflected in these totals is skipped rather than added again.
 */
@Document("customer_dashboards")
public record CustomerDashboard(
        @Id UUID customerId,
        String name,
        String email,
        long orderCount,
        BigDecimal totalSpent,
        Instant lastOrderAt,
        Map<String, BigDecimal> spentByCategory,
        long appliedSequence) {
}
