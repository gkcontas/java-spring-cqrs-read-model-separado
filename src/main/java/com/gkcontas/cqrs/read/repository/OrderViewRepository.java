package com.gkcontas.cqrs.read.repository;

import com.gkcontas.cqrs.read.document.OrderView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OrderViewRepository extends MongoRepository<OrderView, UUID> {

    List<OrderView> findByCustomerIdOrderByPlacedAtDesc(UUID customerId);
}
