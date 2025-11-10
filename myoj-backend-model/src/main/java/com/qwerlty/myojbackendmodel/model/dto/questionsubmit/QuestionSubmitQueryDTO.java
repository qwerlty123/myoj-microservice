package com.qwerlty.myojbackendmodel.model.dto.questionsubmit;

import lombok.Data;

import java.io.Serializable;

@Data
public class QuestionSubmitQueryDTO implements Serializable {
    private Long userId;
    private Long questionId; // 如果需要的话
    private String language;
    // Getters and Setters
}