package com.qwerlty.myojbackendaiservice.model.dto.generation;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DraftRepairPatch {
    private String baseHash;
    private List<DraftRepairOperation> operations = new ArrayList<>();

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("题目草稿补丁包含未知字段: " + name);
    }
}
