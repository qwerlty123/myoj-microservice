package com.qwerlty.myojbackendaiservice.model.dto.generation;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class CandidateTestInput {
    private String input;
    private String category;
    private Boolean oracleEligible = true;
    private List<String> riskIds = new ArrayList<>();
}
