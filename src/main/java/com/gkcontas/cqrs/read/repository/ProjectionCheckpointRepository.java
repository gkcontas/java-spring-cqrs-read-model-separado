package com.gkcontas.cqrs.read.repository;

import com.gkcontas.cqrs.read.document.ProjectionCheckpoint;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ProjectionCheckpointRepository extends MongoRepository<ProjectionCheckpoint, String> {
}
