package com.gkcontas.cqrs;

import com.gkcontas.cqrs.write.model.Customer;
import com.gkcontas.cqrs.write.model.Product;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two models must answer the same thing.
 *
 * <p>A read model that is fast and wrong is not an optimisation, and it is an easy thing
 * to ship: the projection is written once, by hand, and from then on nothing compares it
 * with the source it was derived from.
 */
class ModelAgreementIntegrationTest extends IntegrationTestBase {

    @Test
    void theDashboardMatchesWhatTheWriteModelComputes() {
        Customer ana = customer("Ana");
        Product keyboard = product("KB-001", "peripherals", "150.00");
        Product monitor = product("MON-001", "monitors", "900.00");
        Product headset = product("HS-001", "audio", "250.00");

        commands.placeOrder(ana.getId(), Map.of(keyboard.getId(), 2, monitor.getId(), 1));
        commands.placeOrder(ana.getId(), Map.of(headset.getId(), 3));
        commands.placeOrder(ana.getId(), Map.of(keyboard.getId(), 1));
        projector.catchUp();

        Map<String, BigDecimal> fromWrite = new HashMap<>();
        orders.sumByCategory(ana.getId()).forEach(row ->
                fromWrite.put((String) row[0], (BigDecimal) row[1]));

        Map<String, BigDecimal> fromRead = dashboards.findById(ana.getId())
                .orElseThrow().spentByCategory();

        assertThat(fromRead).hasSameSizeAs(fromWrite);
        fromWrite.forEach((category, total) ->
                assertThat(fromRead.get(category)).isEqualByComparingTo(total));
    }

    @Test
    void theOrderViewMatchesTheOrderItWasDerivedFrom() {
        Customer ana = customer("Ana");
        Product keyboard = product("KB-002", "peripherals", "320.00");
        var placed = place(ana, keyboard, 3);
        projector.catchUp();

        var fromWrite = orders.findWithItemsById(placed.orderId()).orElseThrow();
        var fromRead = orderViews.findById(placed.orderId()).orElseThrow();

        assertThat(fromRead.total()).isEqualByComparingTo(fromWrite.getTotal());
        assertThat(fromRead.items()).hasSize(fromWrite.getItems().size());
        assertThat(fromRead.customerName()).isEqualTo(fromWrite.getCustomer().getName());
    }
}
