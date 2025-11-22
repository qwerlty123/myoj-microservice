package com.qwerlty.myojbackendaiservice.model.dto.generation;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TestInputPlan {
    private List<GeneratedTestInput> inputs = new ArrayList<>();
}
