package com.gkcontas.cqrs.read.repository;

import com.gkcontas.cqrs.read.document.CustomerDashboard;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CustomerDashboardRepository extends MongoRepository<CustomerDashboard, UUID> {
}
