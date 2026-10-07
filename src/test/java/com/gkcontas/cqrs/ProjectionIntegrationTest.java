package com.gkcontas.cqrs;

import com.gkcontas.cqrs.read.document.CustomerDashboard;
import com.gkcontas.cqrs.read.document.OrderView;
import com.gkcontas.cqrs.read.document.ProjectionCheckpoint;
import com.gkcontas.cqrs.write.model.Customer;
import com.gkcontas.cqrs.write.model.OrderStatus;
import com.gkcontas.cqrs.write.model.Product;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectionIntegrationTest extends IntegrationTestBase {

    @Test
    void buildsTheOrderViewWithEverythingTheScreenNeeds() {
        Customer ana = customer("Ana");
        Product keyboard = product("KB-001", "peripherals", "450.00");
        var placed = place(ana, keyboard, 2);

        projector.catchUp();

        assertThat(orderViews.findById(placed.orderId())).hasValueSatisfying(view -> {
            // No joins were needed to produce any of this.
            assertThat(view.customerName()).isEqualTo("Ana");
            assertThat(view.items()).singleElement().satisfies(line -> {
                assertThat(line.sku()).isEqualTo("KB-001");
                assertThat(line.category()).isEqualTo("peripherals");
                assertThat(line.lineTotal()).isEqualByComparingTo("900.00");
            });
            assertThat(view.total()).isEqualByComparingTo("900.00");
        });
    }

    @Test
    void aggregatesTheDashboardAcrossOrders() {
        Customer ana = customer("Ana");
        Product keyboard = product("KB-002", "peripherals", "100.00");
        Product monitor = product("MON-001", "monitors", "1000.00");

        commands.placeOrder(ana.getId(), Map.of(keyboard.getId(), 2));
        commands.placeOrder(ana.getId(), Map.of(monitor.getId(), 1));
        projector.catchUp();

        assertThat(dashboards.findById(ana.getId())).hasValueSatisfying(dashboard -> {
            assertThat(dashboard.orderCount()).isEqualTo(2);
            assertThat(dashboard.totalSpent()).isEqualByComparingTo("1200.00");
            assertThat(dashboard.spentByCategory())
                    .containsEntry("peripherals", new BigDecimal("200.00"))
                    .containsEntry("monitors", new BigDecimal("1000.00"));
        });
    }

    @Test
    void doesNotCountAnEventTwiceWhenItIsReplayed() {
        Customer ana = customer("Ana");
        Product keyboard = product("KB-003", "peripherals", "200.00");
        place(ana, keyboard, 1);
        projector.catchUp();

        // Rewinding the checkpoint is exactly what a crash mid-batch produces.
        checkpoints.save(new ProjectionCheckpoint(ProjectionCheckpoint.ORDERS, 0,
                java.time.Instant.now()));
        projector.catchUp();

        // The totals are maintained incrementally, so without the per-document sequence
        // check this would read 400.00 and nobody would notice until a customer did.
        assertThat(dashboards.findById(ana.getId())).hasValueSatisfying(dashboard -> {
            assertThat(dashboard.orderCount()).isEqualTo(1);
            assertThat(dashboard.totalSpent()).isEqualByComparingTo("200.00");
        });
    }

    @Test
    void appliesStatusChangesInOrder() {
        Customer ana = customer("Ana");
        Product keyboard = product("KB-004", "peripherals", "150.00");
        var placed = place(ana, keyboard, 1);
        commands.changeStatus(placed.orderId(), OrderStatus.PAID);

        projector.catchUp();

        assertThat(orderViews.findById(placed.orderId()))
                .hasValueSatisfying(view -> assertThat(view.status()).isEqualTo("PAID"));
    }

    @Test
    void refusesAnEventOlderThanWhatIsAlreadyProjected() {
        Customer ana = customer("Ana");
        Product keyboard = product("KB-005", "peripherals", "150.00");
        var placed = place(ana, keyboard, 1);
        commands.changeStatus(placed.orderId(), OrderStatus.PAID);
        projector.catchUp();

        // Replaying everything also replays the OrderPlaced that said "PLACED". Without
        // the version check the read model would move backwards.
        checkpoints.save(new ProjectionCheckpoint(ProjectionCheckpoint.ORDERS, 0,
                java.time.Instant.now()));
        projector.catchUp();

        assertThat(orderViews.findById(placed.orderId()))
                .hasValueSatisfying(view -> assertThat(view.status()).isEqualTo("PAID"));
    }

    @Test
    void rebuildsTheWholeReadModelFromTheEventStream() {
        Customer ana = customer("Ana");
        Product keyboard = product("KB-006", "peripherals", "90.00");
        Product monitor = product("MON-002", "monitors", "700.00");
        commands.placeOrder(ana.getId(), Map.of(keyboard.getId(), 3));
        commands.placeOrder(ana.getId(), Map.of(monitor.getId(), 1));
        projector.catchUp();

        CustomerDashboard before = dashboards.findById(ana.getId()).orElseThrow();
        OrderView viewBefore = orderViews.findAll().getFirst();

        var result = projector.rebuild();

        // The capability that justifies much of the pattern: changing a document's shape,
        // or fixing a projection bug, is a deploy plus a rebuild rather than a migration
        // over data that was only ever derived.
        assertThat(result.eventsApplied()).isEqualTo(2);
        assertThat(dashboards.findById(ana.getId())).hasValueSatisfying(after -> {
            assertThat(after.orderCount()).isEqualTo(before.orderCount());
            assertThat(after.totalSpent()).isEqualByComparingTo(before.totalSpent());
            assertThat(after.spentByCategory()).isEqualTo(before.spentByCategory());
        });
        assertThat(orderViews.findById(viewBefore.orderId())).isPresent();
    }

    @Test
    void advancesTheCheckpointOnlyAfterApplying() {
        Customer ana = customer("Ana");
        Product keyboard = product("KB-007", "peripherals", "50.00");
        var placed = place(ana, keyboard, 1);

        assertThat(projector.checkpoint()).isZero();
        projector.catchUp();

        assertThat(projector.checkpoint()).isEqualTo(placed.sequence());
    }
}
