package com.qwerlty.myojbackendaiservice.model.dto.generation;

import lombok.Data;

@Data
public class CaseEvidence {
    private int caseIndex;
    private String inputDigest;
    private boolean validatorPassed;
    private String expectedOutput;
    private String javaOutput;
    private String cppOutput;
    private String oracleOutput;
    private boolean crossLanguageMatched;
    private boolean oracleMatched;
}
