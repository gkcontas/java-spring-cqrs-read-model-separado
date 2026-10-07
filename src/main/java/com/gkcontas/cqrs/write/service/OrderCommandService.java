package com.gkcontas.cqrs.write.service;

import com.gkcontas.cqrs.event.DomainEvent;
import com.gkcontas.cqrs.event.EventRecorder;
import com.gkcontas.cqrs.event.OrderEvents;
import com.gkcontas.cqrs.write.model.Customer;
import com.gkcontas.cqrs.write.model.Order;
import com.gkcontas.cqrs.write.model.OrderItem;
import com.gkcontas.cqrs.write.model.OrderStatus;
import com.gkcontas.cqrs.write.model.Product;
import com.gkcontas.cqrs.write.repository.CustomerRepository;
import com.gkcontas.cqrs.write.repository.OrderRepository;
import com.gkcontas.cqrs.write.repository.ProductRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The command side: it changes state and announces what changed.
 *
 * <p>Note what it does not do — it never reads the read model, and it never returns one.
 * The only thing it hands back is the sequence of the event it just wrote, which is what a
 * caller needs in order to ask the read side for a view that already includes this change.
 */
@Service
public class OrderCommandService {

    private final OrderRepository orders;
    private final CustomerRepository customers;
    private final ProductRepository products;
    private final EventRecorder events;
    private final Clock clock;

    public OrderCommandService(OrderRepository orders, CustomerRepository customers,
                               ProductRepository products, EventRecorder events, Clock clock) {
        this.orders = orders;
        this.customers = customers;
        this.products = products;
        this.events = events;
        this.clock = clock;
    }

    /**
     * @param orderId  the order that was created
     * @param sequence the position in the event stream that reflects it, which the caller
     *                 can quote on a read to avoid seeing a stale view
     */
    public record CommandResult(UUID orderId, long sequence) {
    }

    @Transactional
    public CommandResult placeOrder(UUID customerId, Map<UUID, Integer> quantitiesByProduct) {
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("No customer " + customerId));

        Order order = new Order(UUID.randomUUID(), customer, clock.instant());
        quantitiesByProduct.forEach((productId, quantity) -> {
            Product product = products.findById(productId)
                    .orElseThrow(() -> new IllegalArgumentException("No product " + productId));
            order.addItem(product, quantity);
        });
        orders.save(order);

        DomainEvent event = events.record(order.getId(), toPlaced(order));
        return new CommandResult(order.getId(), event.getSequence());
    }

    @Transactional
    public CommandResult changeStatus(UUID orderId, OrderStatus next) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("No order " + orderId));
        order.changeStatus(next, clock.instant());

        DomainEvent event = events.record(order.getId(), new OrderEvents.OrderStatusChanged(
                order.getId(), order.getVersion(), order.getCustomer().getId(),
                next.name(), order.getTotal(), order.getUpdatedAt()));

        return new CommandResult(order.getId(), event.getSequence());
    }

    /**
     * Builds the event with the data already denormalised.
     *
     * <p>Carrying the customer's name and each product's category means the projector
     * never has to query the write model. That is what keeps the two sides independent —
     * and what would let the read side live on another machine, or belong to another team,
     * without a single extra query crossing over.
     */
    private static OrderEvents.OrderPlaced toPlaced(Order order) {
        List<OrderEvents.OrderLine> lines = order.getItems().stream()
                .map(item -> new OrderEvents.OrderLine(
                        item.getProduct().getSku(),
                        item.getProduct().getName(),
                        item.getProduct().getCategory(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.lineTotal()))
                .toList();

        return new OrderEvents.OrderPlaced(order.getId(), order.getVersion(),
                order.getCustomer().getId(), order.getCustomer().getName(),
                order.getCustomer().getEmail(), order.getTotal(), lines, order.getPlacedAt());
    }

    /** Used by the comparison endpoint to answer from the write model. */
    @Transactional(readOnly = true)
    public Order loadWithItems(UUID orderId) {
        return orders.findWithItemsById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("No order " + orderId));
    }

    @Transactional(readOnly = true)
    public List<OrderItem> itemsOf(UUID orderId) {
        return loadWithItems(orderId).getItems();
    }
}
