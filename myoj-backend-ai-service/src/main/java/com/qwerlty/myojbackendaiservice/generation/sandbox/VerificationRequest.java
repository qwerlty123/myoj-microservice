package com.qwerlty.myojbackendaiservice.generation.sandbox;

import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateTestInput;
import com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ReferenceSolution;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ValidationPrograms;

import java.util.List;

public record VerificationRequest(
        VerificationPurpose purpose,
        List<CandidateTestInput> candidates,
        List<ReferenceSolution> solutions,
        ValidationPrograms programs,
        JudgeConfigValue judgeConfig) {
}
