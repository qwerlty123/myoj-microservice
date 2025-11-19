package com.qwerlty.myojbackendaiservice.model.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.util.Date;

@Data
public class AiFeedbackTaskVO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long submissionId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long questionId;
    private String status;
    private AiFeedbackResultVO result;
    private String errorCode;
    private String lastError;
    private Date createTime;
    private Date updateTime;
}
