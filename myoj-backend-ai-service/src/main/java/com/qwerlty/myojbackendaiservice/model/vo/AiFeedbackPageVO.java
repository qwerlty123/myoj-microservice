package com.qwerlty.myojbackendaiservice.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class AiFeedbackPageVO {
    private List<AiFeedbackTaskVO> records;
    private long total;
    private int current;
    private int pageSize;
}
