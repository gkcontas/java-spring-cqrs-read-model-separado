package com.gkcontas.cqrs.api;

import com.gkcontas.cqrs.write.model.Customer;
import com.gkcontas.cqrs.write.model.Product;
import com.gkcontas.cqrs.write.repository.CustomerRepository;
import com.gkcontas.cqrs.write.repository.ProductRepository;
import com.gkcontas.cqrs.write.service.OrderCommandService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Synthetic volume, because the comparison is meaningless without it.
 *
 * <p>On ten orders both models answer instantly and the read model looks like ceremony.
 * The difference only appears once the write-side aggregation has something to aggregate,
 * which is also the honest threshold for adopting the pattern at all.
 */
@RestController
class LoadController {

    private static final String[] CATEGORIES = {"peripherals", "monitors", "audio", "storage"};

    private final CustomerRepository customers;
    private final ProductRepository products;
    private final OrderCommandService commands;

    LoadController(CustomerRepository customers, ProductRepository products,
                   OrderCommandService commands) {
        this.customers = customers;
        this.products = products;
        this.commands = commands;
    }

    record LoadResult(int customers, int products, int orders, long elapsedMillis) {
    }

    @PostMapping("/load")
    LoadResult generate(@RequestParam(defaultValue = "5") int customerCount,
                        @RequestParam(defaultValue = "200") int ordersPerCustomer) {
        long startedAt = System.nanoTime();

        List<Product> catalogue = ensureCatalogue();
        List<Customer> people = new ArrayList<>();
        for (int index = 0; index < customerCount; index++) {
            people.add(customers.save(new Customer(UUID.randomUUID(),
                    "Customer %d".formatted(index),
                    "customer-%s@example.com".formatted(UUID.randomUUID()))));
        }

        int orders = 0;
        for (Customer customer : people) {
            for (int index = 0; index < ordersPerCustomer; index++) {
                commands.placeOrder(customer.getId(), randomItems(catalogue));
                orders++;
            }
        }

        return new LoadResult(people.size(), catalogue.size(), orders,
                (System.nanoTime() - startedAt) / 1_000_000);
    }

    private List<Product> ensureCatalogue() {
        if (!products.findAll().isEmpty()) {
            return products.findAll();
        }
        List<Product> created = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            created.add(products.save(new Product(UUID.randomUUID(),
                    "SKU-%03d".formatted(index),
                    "Product %d".formatted(index),
                    CATEGORIES[index % CATEGORIES.length],
                    BigDecimal.valueOf(50 + index * 7L))));
        }
        return created;
    }

    private static Map<UUID, Integer> randomItems(List<Product> catalogue) {
        Map<UUID, Integer> items = new HashMap<>();
        int lines = ThreadLocalRandom.current().nextInt(1, 4);
        for (int index = 0; index < lines; index++) {
            Product product = catalogue.get(ThreadLocalRandom.current().nextInt(catalogue.size()));
            items.put(product.getId(), ThreadLocalRandom.current().nextInt(1, 4));
        }
        return items;
    }
}
