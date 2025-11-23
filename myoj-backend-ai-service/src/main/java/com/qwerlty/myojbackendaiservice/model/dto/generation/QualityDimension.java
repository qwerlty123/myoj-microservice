package com.qwerlty.myojbackendaiservice.model.dto.generation;

public record QualityDimension(
        String name,
        int weight,
        String status,
        Integer score) {
}
