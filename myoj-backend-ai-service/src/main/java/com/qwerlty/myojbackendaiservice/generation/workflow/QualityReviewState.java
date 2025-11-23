package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.qwerlty.myojbackendaiservice.model.dto.generation.CaseEvidence;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemSpec;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ReferenceSolution;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ValidationPrograms;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class QualityReviewState {
    private GeneratedProblemSpec specification;
    private List<ReferenceSolution> solutions = new ArrayList<>();
    private ValidationPrograms programs;
    private List<CaseEvidence> baselineEvidence = new ArrayList<>();
}
