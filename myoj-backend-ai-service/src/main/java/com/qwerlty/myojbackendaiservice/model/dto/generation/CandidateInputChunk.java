package com.qwerlty.myojbackendaiservice.model.dto.generation;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CandidateInputChunk {
    @JsonPropertyDescription("片段类型：LITERAL、REPEAT、RANGE 或 CYCLE")
    private String type;

    @JsonPropertyDescription("LITERAL/REPEAT 使用的文本")
    private String value;

    @JsonPropertyDescription("CYCLE 循环使用的短文本列表")
    private List<String> values = new ArrayList<>();

    @JsonPropertyDescription("REPEAT/RANGE/CYCLE 产生的元素数量，最大 1000000")
    private Integer count;

    @JsonPropertyDescription("RANGE 的首个整数")
    private Long start;

    @JsonPropertyDescription("RANGE 的整数步长，默认 1")
    private Long step;

    @JsonPropertyDescription("元素之间的分隔符；片段之间不会自动添加分隔符")
    private String separator;

    public static CandidateInputChunk literal(String value) {
        CandidateInputChunk chunk = new CandidateInputChunk();
        chunk.setType("LITERAL");
        chunk.setValue(value);
        return chunk;
    }

    public static CandidateInputChunk range(long start, long step, int count, String separator) {
        CandidateInputChunk chunk = new CandidateInputChunk();
        chunk.setType("RANGE");
        chunk.setStart(start);
        chunk.setStep(step);
        chunk.setCount(count);
        chunk.setSeparator(separator);
        return chunk;
    }
}
