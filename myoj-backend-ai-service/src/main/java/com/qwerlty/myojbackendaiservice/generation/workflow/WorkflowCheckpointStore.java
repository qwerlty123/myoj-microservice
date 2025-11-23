package com.qwerlty.myojbackendaiservice.generation.workflow;

import java.util.Optional;

public interface WorkflowCheckpointStore {
    Optional<WorkflowCheckpoint> load();

    void save(WorkflowCheckpoint checkpoint);

    void clear();

    static WorkflowCheckpointStore noop() {
        return new WorkflowCheckpointStore() {
            public Optional<WorkflowCheckpoint> load() { return Optional.empty(); }
            public void save(WorkflowCheckpoint checkpoint) { }
            public void clear() { }
        };
    }
}
