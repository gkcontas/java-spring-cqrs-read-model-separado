package com.gkcontas.cqrs;

import com.gkcontas.cqrs.event.DomainEventRepository;
import com.gkcontas.cqrs.read.projection.OrderProjector;
import com.gkcontas.cqrs.read.repository.CustomerDashboardRepository;
import com.gkcontas.cqrs.read.repository.OrderViewRepository;
import com.gkcontas.cqrs.read.repository.ProjectionCheckpointRepository;
import com.gkcontas.cqrs.write.model.Customer;
import com.gkcontas.cqrs.write.model.Product;
import com.gkcontas.cqrs.write.repository.CustomerRepository;
import com.gkcontas.cqrs.write.repository.OrderRepository;
import com.gkcontas.cqrs.write.repository.ProductRepository;
import com.gkcontas.cqrs.write.service.OrderCommandService;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Both stores, started once per JVM.
 *
 * <p>The scheduled projector is switched off for the whole suite: every test drives
 * {@code catchUp()} at the moment it chooses. That is not only about speed — holding the
 * projector is the only way to observe the window in which the read model is behind, which
 * is the window users complain about.
 */
@SpringBootTest
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shop")
            .withUsername("shop")
            .withPassword("shop");

    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    static {
        POSTGRES.start();
        MONGO.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl("shop_read"));
        registry.add("app.projection.enabled", () -> false);
    }

    @Autowired
    protected OrderCommandService commands;

    @Autowired
    protected OrderProjector projector;

    @Autowired
    protected OrderRepository orders;

    @Autowired
    protected CustomerRepository customers;

    @Autowired
    protected ProductRepository products;

    @Autowired
    protected DomainEventRepository events;

    @Autowired
    protected OrderViewRepository orderViews;

    @Autowired
    protected CustomerDashboardRepository dashboards;

    @Autowired
    protected ProjectionCheckpointRepository checkpoints;

    @BeforeEach
    void reset() {
        orders.deleteAll();
        events.deleteAll();
        products.deleteAll();
        customers.deleteAll();
        orderViews.deleteAll();
        dashboards.deleteAll();
        checkpoints.deleteAll();
    }

    protected Customer customer(String name) {
        return customers.save(new Customer(UUID.randomUUID(), name,
                "%s@example.com".formatted(UUID.randomUUID())));
    }

    protected Product product(String sku, String category, String price) {
        return products.save(new Product(UUID.randomUUID(), sku, "Product " + sku, category,
                new BigDecimal(price)));
    }

    protected OrderCommandService.CommandResult place(Customer customer, Product product,
                                                      int quantity) {
        return commands.placeOrder(customer.getId(), Map.of(product.getId(), quantity));
    }
}
