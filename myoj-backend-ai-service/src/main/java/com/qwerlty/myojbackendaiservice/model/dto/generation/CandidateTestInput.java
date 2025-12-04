package com.qwerlty.myojbackendaiservice.model.dto.generation;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class CandidateTestInput {
    @JsonPropertyDescription("小输入的原文，最多 8 KiB；大输入必须改用 chunks，input 与 chunks 二选一")
    private String input;

    @JsonPropertyDescription("大输入的压缩片段，最多 32 个；input 与 chunks 二选一")
    private List<CandidateInputChunk> chunks = new ArrayList<>();

    @JsonPropertyDescription("必填测试类别，只能是 NORMAL、BOUNDARY、MAXIMUM、ADVERSARIAL 之一")
    private String category;
    private Boolean oracleEligible = true;
    private List<String> riskIds = new ArrayList<>();
}
