package com.gkcontas.cqrs.read.document;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * How far the projector has read.
 *
 * <p>It lives in the read store on purpose: dropping the read database has to drop the
 * position with it, otherwise a rebuild would resume from the middle and produce a model
 * missing everything that came before.
 */
@Document("projection_checkpoints")
public record ProjectionCheckpoint(@Id String name, long lastSequence, Instant updatedAt) {

    public static final String ORDERS = "orders";
}
