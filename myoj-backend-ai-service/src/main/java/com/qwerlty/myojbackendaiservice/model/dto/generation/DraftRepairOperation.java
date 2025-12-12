package com.qwerlty.myojbackendaiservice.model.dto.generation;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

@Data
public class DraftRepairOperation {
    private String target;
    private Object afterValue;
    private String reason;

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("题目草稿补丁操作包含未知字段: " + name);
    }
}
