package com.qwerlty.myojbackendaiservice.manager;

import com.qwerlty.myojbackendaiservice.model.entity.AiProblemGenerationTask;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks work owned by this process so shutdown never resets another instance's task. */
@Component
public class GenerationExecutionRegistry {
    private final ConcurrentHashMap<Long, AiProblemGenerationTask> running = new ConcurrentHashMap<>();

    public void register(AiProblemGenerationTask task) {
        running.put(task.getId(), task);
    }

    public void unregister(Long taskId) {
        running.remove(taskId);
    }

    public List<AiProblemGenerationTask> snapshot() {
        return List.copyOf(running.values());
    }
}
