package com.gkcontas.cqrs.write.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The write model: normalised, and shaped by the rules rather than by any screen.
 *
 * <p>Answering "what did this customer spend by category" from here means joining four
 * tables and grouping over the customer's whole history. That is not a flaw in the model —
 * it is what a model designed for correct writes looks like, and it is exactly the reason
 * for having a second one designed for reads.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
public class Order {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    /**
     * Incremented on every change to this order.
     *
     * <p>Not optimistic locking — the projection uses it to reject an event that describes
     * an older state than the one it already applied, which happens whenever events are
     * replayed or arrive out of order.
     */
    @Column(nullable = false)
    private long version;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();

    public Order(UUID id, Customer customer, Instant now) {
        this.id = id;
        this.customer = customer;
        this.status = OrderStatus.PLACED;
        this.total = BigDecimal.ZERO;
        this.version = 1;
        this.placedAt = now;
        this.updatedAt = now;
    }

    public void addItem(Product product, int quantity) {
        items.add(new OrderItem(UUID.randomUUID(), this, product, quantity, product.getPrice()));
        total = items.stream().map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public void changeStatus(OrderStatus next, Instant now) {
        status = next;
        version++;
        updatedAt = now;
    }
}
