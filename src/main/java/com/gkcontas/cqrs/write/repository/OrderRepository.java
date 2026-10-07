package com.gkcontas.cqrs.write.repository;

import com.gkcontas.cqrs.write.model.Order;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    /**
     * The order with everything the summary needs, in one query.
     *
     * <p>Even written as well as it can be, this is a three-way join returning one row per
     * item, which the ORM then has to collapse. It is the comparison's "before".
     */
    @EntityGraph(attributePaths = {"customer", "items", "items.product"})
    Optional<Order> findWithItemsById(UUID id);

    /**
     * The customer dashboard, computed from the write model.
     *
     * <p>Four tables, a group by, and a scan proportional to everything the customer has
     * ever bought. Fine for one customer on a quiet afternoon; the read model exists
     * because dashboards are not opened on quiet afternoons.
     */
    @Query("""
            SELECT i.product.category, SUM(i.unitPrice * i.quantity)
              FROM Order o JOIN o.items i
             WHERE o.customer.id = :customerId AND o.status <> com.gkcontas.cqrs.write.model.OrderStatus.CANCELLED
             GROUP BY i.product.category
            """)
    List<Object[]> sumByCategory(@Param("customerId") UUID customerId);

    @Query("""
            SELECT COUNT(o), COALESCE(SUM(o.total), 0), MAX(o.placedAt)
              FROM Order o
             WHERE o.customer.id = :customerId AND o.status <> com.gkcontas.cqrs.write.model.OrderStatus.CANCELLED
            """)
    List<Object[]> summarise(@Param("customerId") UUID customerId);

    List<Order> findAllByOrderByPlacedAtAsc();
}
