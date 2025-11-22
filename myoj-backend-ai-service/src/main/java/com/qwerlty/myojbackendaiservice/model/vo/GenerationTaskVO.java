package com.qwerlty.myojbackendaiservice.model.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemDraft;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GenerationValidationReport;
import lombok.Data;

import java.util.Date;

@Data
public class GenerationTaskVO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;
    private String mode;
    private String status;
    private String stage;
    private Integer progress;
    private GeneratedProblemDraft draft;
    private GenerationValidationReport validation;
    private String errorCode;
    private String lastError;
    private Date createTime;
    private Date updateTime;
}
