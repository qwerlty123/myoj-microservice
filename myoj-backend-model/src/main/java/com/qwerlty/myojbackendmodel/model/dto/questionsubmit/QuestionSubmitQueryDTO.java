package com.qwerlty.myojbackendmodel.model.dto.questionsubmit;

import lombok.Data;

import java.io.Serializable;

@Data
public class QuestionSubmitQueryDTO implements Serializable {
    private Long userId;
    private Long questionId; // 如果需要的话
    private String language;
    /** 内部调用最多返回多少条；为空时保持原有不限制行为。 */
    private Integer limit;
    // Getters and Setters
}
