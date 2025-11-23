package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.qwerlty.myojbackendaiservice.model.dto.generation.CoveragePlan;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemSpec;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ReferenceSolution;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ValidationPrograms;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TestCaseGenerationState {
    private GeneratedProblemSpec specification;
    private CoveragePlan coveragePlan;
    private List<ReferenceSolution> solutions = new ArrayList<>();
    private ValidationPrograms programs;
    private List<AcceptedCaseState> acceptedCases = new ArrayList<>();
    private int rejectedCount;
    private int rounds;
}
