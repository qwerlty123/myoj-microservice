package com.qwerlty.myojbackendaiservice.model.dto.generation;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class QualityIssue {
    private String id;
    private String dimension;
    private String severity;
    private String title;
    private String detail;
    private List<String> evidence = new ArrayList<>();
}
