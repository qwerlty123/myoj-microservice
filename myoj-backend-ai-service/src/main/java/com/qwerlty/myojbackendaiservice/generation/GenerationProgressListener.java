package com.qwerlty.myojbackendaiservice.generation;

import com.qwerlty.myojbackendaiservice.model.enums.GenerationStage;

@FunctionalInterface
public interface GenerationProgressListener {
    void onStage(GenerationStage stage);
}
