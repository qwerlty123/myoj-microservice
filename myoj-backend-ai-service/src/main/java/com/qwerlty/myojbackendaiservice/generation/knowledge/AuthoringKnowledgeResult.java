package com.qwerlty.myojbackendaiservice.generation.knowledge;

import java.util.List;

public record AuthoringKnowledgeResult(boolean available,
                                       String message,
                                       List<KnowledgeHit> hits) {
    public AuthoringKnowledgeResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    public record KnowledgeHit(String docId, String title, String content) {
    }
}
