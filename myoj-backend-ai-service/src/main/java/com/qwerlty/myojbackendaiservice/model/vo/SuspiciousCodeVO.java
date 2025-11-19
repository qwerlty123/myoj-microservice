package com.qwerlty.myojbackendaiservice.model.vo;

import lombok.Data;

@Data
public class SuspiciousCodeVO {
    private Integer startLine;
    private Integer endLine;
    private String reason;
}
