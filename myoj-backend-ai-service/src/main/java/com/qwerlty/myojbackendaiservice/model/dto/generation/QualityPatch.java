package com.qwerlty.myojbackendaiservice.model.dto.generation;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class QualityPatch {
    private String id;
    private String operation;
    private String target;
    private Object beforeValue;
    private Object afterValue;
    private String beforeHash;
    private String reason;
    private List<String> evidenceRefs = new ArrayList<>();
}
