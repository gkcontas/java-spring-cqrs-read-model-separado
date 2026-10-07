package com.gkcontas.cqrs.api;

import com.gkcontas.cqrs.read.document.CustomerDashboard;
import com.gkcontas.cqrs.read.document.OrderView;
import com.gkcontas.cqrs.read.projection.ReadModelQuery;
import com.gkcontas.cqrs.write.model.Order;
import com.gkcontas.cqrs.write.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The same question, answered by both models, with the times side by side.
 *
 * <p>Without this the argument for CQRS is an assertion. With it, it is two numbers — and
 * the numbers also show when the pattern is <em>not</em> worth it: on a small dataset the
 * difference is noise, and paying for two models to save a millisecond is a bad trade.
 */
@RestController
@RequestMapping("/comparison")
class ComparisonController {

    private final OrderRepository orders;
    private final ReadModelQuery queries;

    ComparisonController(OrderRepository orders, ReadModelQuery queries) {
        this.orders = orders;
        this.queries = queries;
    }

    record Comparison(String question, long writeModelMicros, long readModelMicros,
                      String agreement) {
    }

    @GetMapping("/orders/{id}")
    @Transactional(readOnly = true)
    Comparison order(@PathVariable UUID id) {
        Timed<BigDecimal> fromWrite = time(() -> {
            Order order = orders.findWithItemsById(id).orElseThrow();
            // Touching the items is what forces the join to be paid for; a lazy collection
            // that is never read makes any benchmark look wonderful.
            order.getItems().forEach(item -> item.getProduct().getCategory());
            return order.getTotal();
        });
        Timed<BigDecimal> fromRead = time(() -> queries.order(id).map(OrderView::total).orElseThrow());

        return new Comparison("order summary", fromWrite.micros, fromRead.micros,
                agreement(fromWrite.value, fromRead.value));
    }

    @GetMapping("/dashboards/{customerId}")
    @Transactional(readOnly = true)
    Comparison dashboard(@PathVariable UUID customerId) {
        Timed<Map<String, BigDecimal>> fromWrite = time(() -> {
            Map<String, BigDecimal> byCategory = new HashMap<>();
            orders.sumByCategory(customerId).forEach(row ->
                    byCategory.put((String) row[0], (BigDecimal) row[1]));
            orders.summarise(customerId);
            return byCategory;
        });
        Timed<Map<String, BigDecimal>> fromRead = time(() ->
                queries.dashboard(customerId).map(CustomerDashboard::spentByCategory).orElseThrow());

        return new Comparison("spend by category", fromWrite.micros, fromRead.micros,
                agreement(fromWrite.value, fromRead.value));
    }

    private static String agreement(Object left, Object right) {
        // The comparison is only interesting if both sides say the same thing. A read
        // model that is fast and wrong is not an optimisation.
        return left.equals(right) ? "same result" : "DIFFERENT: %s vs %s".formatted(left, right);
    }

    private static <T> Timed<T> time(Supplier<T> work) {
        long startedAt = System.nanoTime();
        T value = work.get();
        return new Timed<>(value, (System.nanoTime() - startedAt) / 1_000);
    }

    private record Timed<T>(T value, long micros) {
    }
}
