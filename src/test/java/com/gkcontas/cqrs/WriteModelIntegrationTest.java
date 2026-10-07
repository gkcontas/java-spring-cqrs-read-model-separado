package com.gkcontas.cqrs;

import com.gkcontas.cqrs.event.DomainEvent;
import com.gkcontas.cqrs.write.model.Customer;
import com.gkcontas.cqrs.write.model.OrderStatus;
import com.gkcontas.cqrs.write.model.Product;
import com.gkcontas.cqrs.write.service.OrderCommandService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WriteModelIntegrationTest extends IntegrationTestBase {

    @Test
    void storesTheOrderAndItsEventInOneTransaction() {
        Customer ana = customer("Ana");
        Product keyboard = product("KB-001", "peripherals", "450.00");

        OrderCommandService.CommandResult result = place(ana, keyboard, 2);

        assertThat(orders.findById(result.orderId())).isPresent();
        assertThat(events.findAll()).singleElement().satisfies(event -> {
            assertThat(event.getType()).isEqualTo("OrderPlaced");
            assertThat(event.getAggregateId()).isEqualTo(result.orderId());
            assertThat(event.getSequence()).isEqualTo(result.sequence());
        });
    }

    @Test
    void writesEverythingTheProjectionNeedsIntoTheEvent() {
        Customer ana = customer("Ana");
        Product keyboard = product("KB-002", "peripherals", "100.00");

        place(ana, keyboard, 3);

        // The projector must never have to query the write model. Denormalising here is
        // what keeps the two sides independent enough to live apart later.
        DomainEvent event = events.findAll().getFirst();
        assertThat(event.getPayload())
                .contains("\"customerName\":\"Ana\"")
                .contains("\"category\":\"peripherals\"")
                .contains("\"sku\":\"KB-002\"");
    }

    @Test
    void incrementsTheVersionOnEveryChange() {
        Customer ana = customer("Ana");
        Product keyboard = product("KB-003", "peripherals", "80.00");
        var placed = place(ana, keyboard, 1);

        commands.changeStatus(placed.orderId(), OrderStatus.PAID);

        assertThat(orders.findById(placed.orderId()).orElseThrow().getVersion()).isEqualTo(2);
        assertThat(events.count()).isEqualTo(2);
    }

    @Test
    void handsBackASequenceThatGrowsMonotonically() {
        Customer ana = customer("Ana");
        Product keyboard = product("KB-004", "peripherals", "60.00");

        long first = place(ana, keyboard, 1).sequence();
        long second = place(ana, keyboard, 1).sequence();

        // The number the caller quotes to read its own write only works because the
        // database assigns it in order. A timestamp could not: two events in the same
        // millisecond have no order at all.
        assertThat(second).isGreaterThan(first);
    }

    @Test
    void writesNothingWhenTheCommandFails() {
        Customer ana = customer("Ana");

        assertThatThrownBy(() -> commands.placeOrder(ana.getId(),
                java.util.Map.of(java.util.UUID.randomUUID(), 1)))
                .isInstanceOf(IllegalArgumentException.class);

        // The event shares the fate of the change it describes.
        assertThat(orders.count()).isZero();
        assertThat(events.count()).isZero();
    }
}
