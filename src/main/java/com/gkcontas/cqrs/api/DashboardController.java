package com.gkcontas.cqrs.api;

import com.gkcontas.cqrs.read.document.CustomerDashboard;
import com.gkcontas.cqrs.read.projection.ReadModelQuery;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/customers/{id}/dashboard")
class DashboardController {

    private final ReadModelQuery queries;

    DashboardController(ReadModelQuery queries) {
        this.queries = queries;
    }

    @GetMapping
    CustomerDashboard dashboard(@PathVariable UUID id) {
        return queries.dashboard(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "No dashboard for customer " + id));
    }
}
