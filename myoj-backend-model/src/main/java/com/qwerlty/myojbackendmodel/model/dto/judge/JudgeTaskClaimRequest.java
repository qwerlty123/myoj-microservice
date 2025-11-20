package com.qwerlty.myojbackendmodel.model.dto.judge;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JudgeTaskClaimRequest implements Serializable {

    private Long submissionId;

    private Integer judgeAttempt;

    private static final long serialVersionUID = 1L;
}
