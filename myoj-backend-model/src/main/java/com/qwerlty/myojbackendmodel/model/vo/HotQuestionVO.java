package com.qwerlty.myojbackendmodel.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * @author 李天宇
 * @version 1.0
 **/
@Data
public class HotQuestionVO implements Serializable {
    private Long id;
    private String title;
    private List<String> tags;
    private Integer difficulty;
}
