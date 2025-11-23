package com.qwerlty.myojbackendaiservice.model.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.util.Date;

@Data
public class GenerationTaskVO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;
    private String taskType;
    private String status;
    private String stage;
    private Integer progress;
    private JsonNode result;
    private String errorCode;
    private String lastError;
    private Date createTime;
    private Date updateTime;
}
