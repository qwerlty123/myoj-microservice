package com.qwerlty.myojbackendquestionservice.controller.inner;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.qwerlty.myojbackendmodel.model.dto.questionsubmit.QuestionSubmitQueryDTO;
import com.qwerlty.myojbackendmodel.model.entity.Question;
import com.qwerlty.myojbackendmodel.model.entity.QuestionSubmit;
import com.qwerlty.myojbackendquestionservice.service.QuestionService;
import com.qwerlty.myojbackendquestionservice.service.QuestionSubmitService;
import com.qwerlty.myojbackendserviceclient.client.QuestionFeignClient;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * 该服务仅内部调用，不是给前端的
 */
@RestController
@RequestMapping("/inner")
public class QuestionInnerController implements QuestionFeignClient {
    @Resource
    private QuestionService questionService;

    @Resource
    private QuestionSubmitService questionSubmitService;

    /**
     * 根据查询条件批量获取题目提交列表
     *
     * @param questionSubmitQueryDTO
     * @return
     */
    @Override
    @PostMapping("/question_submit/list")
    public List<QuestionSubmit> list(@RequestBody QuestionSubmitQueryDTO questionSubmitQueryDTO) {
        QueryWrapper<QuestionSubmit> submitQueryWrapper = new QueryWrapper<>();
        submitQueryWrapper.eq("userId", questionSubmitQueryDTO.getUserId());
        if (questionSubmitQueryDTO.getQuestionId()!=null){
            submitQueryWrapper.eq("questionId", questionSubmitQueryDTO.getQuestionId());
        }
        return questionSubmitService.list(submitQueryWrapper);
    }

    /**
     * 根据查询条件获取题目
     *
     * @param questionId
     * @return
     */
    @Override
    @GetMapping("/get/one")
    public Question getOne(@RequestParam("questionId") long questionId) {
        return questionService.getOne(new QueryWrapper<Question>().eq("id", questionId));
    }

    /**
     * 根据id获取题目提交记录
     *
     * @param questionSubmitId
     * @return
     */
    @Override
    @GetMapping("/question_submit/get/id")
    public QuestionSubmit getQuestionSubmitById(@RequestParam("questionSubmitId") long questionSubmitId) {
        return questionSubmitService.getById(questionSubmitId);
    }

    @Override
    @GetMapping("/get/id")
    public Question getQuestionById(@RequestParam("questionId") long questionId) {
        return questionService.getById(questionId);
    }

    @Override
    @PostMapping("/question_submit/update/id")
    public Boolean updateQuestionSubmitById(@RequestBody QuestionSubmit questionSubmit) {
        return questionSubmitService.updateById(questionSubmit);
    }
    @Override
    @PostMapping("/question/update/id")
    public Boolean updateQuestionById(@RequestBody Question question) {
        return questionService.updateById(question);
    }
}
