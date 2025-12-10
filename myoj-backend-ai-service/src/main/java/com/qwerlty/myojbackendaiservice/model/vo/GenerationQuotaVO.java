package com.qwerlty.myojbackendaiservice.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDate;

@Data
@AllArgsConstructor
public class GenerationQuotaVO {
    private LocalDate date;
    private int limit;
    private int used;
    private int remaining;
}
