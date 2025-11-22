package com.qwerlty.myojbackendaiservice.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class GenerationTaskPageVO {
    private List<GenerationTaskVO> records;
    private long total;
    private int current;
    private int pageSize;
}
