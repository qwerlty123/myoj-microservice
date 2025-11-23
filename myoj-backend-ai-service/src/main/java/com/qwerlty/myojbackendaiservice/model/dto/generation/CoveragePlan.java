package com.qwerlty.myojbackendaiservice.model.dto.generation;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class CoveragePlan {
    private List<CoverageRisk> dynamicRisks = new ArrayList<>();
}
