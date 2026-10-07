package com.gkcontas.cqrs.api;

import com.gkcontas.cqrs.write.model.Customer;
import com.gkcontas.cqrs.write.model.Product;
import com.gkcontas.cqrs.write.repository.CustomerRepository;
import com.gkcontas.cqrs.write.repository.ProductRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reference data, written straight to the write model.
 *
 * <p>Customers and products are not projected: they are only ever read as part of an
 * order, and the order view already carries their names. A projection whose only consumer
 * is another projection is a cost with no reader.
 */
@RestController
class SetupController {

    private final CustomerRepository customers;
    private final ProductRepository products;

    SetupController(CustomerRepository customers, ProductRepository products) {
        this.customers = customers;
        this.products = products;
    }

    record CustomerRequest(
            @NotBlank(message = "must not be blank") String name,
            @Email(message = "must be a valid e-mail address")
            @NotBlank(message = "must not be blank") String email) {
    }

    record ProductRequest(
            @NotBlank(message = "must not be blank") String sku,
            @NotBlank(message = "must not be blank") String name,
            @NotBlank(message = "must not be blank") String category,
            @NotNull(message = "must not be null")
            @DecimalMin(value = "0.01", message = "must be greater than zero") BigDecimal price) {
    }

    @PostMapping("/customers")
    @ResponseStatus(HttpStatus.CREATED)
    Customer createCustomer(@Valid @RequestBody CustomerRequest request) {
        return customers.save(new Customer(UUID.randomUUID(), request.name(), request.email()));
    }

    @GetMapping("/customers")
    List<Customer> allCustomers() {
        return customers.findAll();
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    Product createProduct(@Valid @RequestBody ProductRequest request) {
        return products.save(new Product(UUID.randomUUID(), request.sku(), request.name(),
                request.category(), request.price()));
    }

    @GetMapping("/products")
    List<Product> allProducts() {
        return products.findAll();
    }
}
