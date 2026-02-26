package com.qwerlty.myojbackendquestionservice.controller;

import cn.hutool.extra.mail.MailUtil;
import cn.hutool.json.JSONUtil;
import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qwerlty.myojbackendcommon.annotation.AuthCheck;
import com.qwerlty.myojbackendcommon.common.BaseResponse;
import com.qwerlty.myojbackendcommon.common.DeleteRequest;
import com.qwerlty.myojbackendcommon.common.ErrorCode;
import com.qwerlty.myojbackendcommon.common.ResultUtils;
import com.qwerlty.myojbackendcommon.constant.RedisConstant;
import com.qwerlty.myojbackendcommon.constant.UserConstant;
import com.qwerlty.myojbackendcommon.exception.BusinessException;
import com.qwerlty.myojbackendcommon.exception.ThrowUtils;
import com.qwerlty.myojbackendmodel.model.dto.question.*;
import com.qwerlty.myojbackendmodel.model.dto.questionsubmit.QuestionSubmitAddRequest;
import com.qwerlty.myojbackendmodel.model.dto.questionsubmit.QuestionSubmitQueryRequest;
import com.qwerlty.myojbackendmodel.model.entity.Question;
import com.qwerlty.myojbackendmodel.model.entity.QuestionSubmit;
import com.qwerlty.myojbackendmodel.model.entity.User;
import com.qwerlty.myojbackendmodel.model.enums.QuestionDifficultyEnum;
import com.qwerlty.myojbackendmodel.model.vo.*;
import com.qwerlty.myojbackendquestionservice.job.CacheClearTask;
import com.qwerlty.myojbackendquestionservice.manager.CountManager;
import com.qwerlty.myojbackendquestionservice.manager.RedisLimiterManager;
import com.qwerlty.myojbackendquestionservice.service.QuestionService;
import com.qwerlty.myojbackendquestionservice.service.QuestionSubmitService;
import com.qwerlty.myojbackendserviceclient.client.UserFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 题目接口
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 * @from <a href="https://yupi.icu">编程导航知识星球</a>
 */
@RestController
@RequestMapping
@Slf4j
public class QuestionController {
    @Resource
    private QuestionService questionService;

    @Resource
    private UserFeignClient userFeignClient;

    @Resource
    private QuestionSubmitService questionSubmitService;

    @Resource
    private RedisLimiterManager redisLimiterManager;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private CacheClearTask cacheClearTask;

    @Resource
    private CountManager countManager;

    // region 增删改查

    /**
     * 创建
     *
     * @param questionAddRequest
     * @param request
     * @return
     */
    @PostMapping("/add")
    public BaseResponse<Long> addQuestion(@RequestBody QuestionAddRequest questionAddRequest, HttpServletRequest request) {
        if (questionAddRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Question question = new Question();
        BeanUtils.copyProperties(questionAddRequest, question);
        List<String> tags = questionAddRequest.getTags();
        if (tags != null) {
            question.setTags(JSONUtil.toJsonStr(tags));
        }
        Integer difficulty = questionAddRequest.getDifficulty();
        if (QuestionDifficultyEnum.getEnumByValue(difficulty) != null) {
            question.setDifficulty(difficulty);
        }
        List<JudgeCase> judgeCase = questionAddRequest.getJudgeCase();
        JudgeConfig judgeConfig = questionAddRequest.getJudgeConfig();
        if (judgeCase != null) {
            question.setJudgeCase(JSONUtil.toJsonStr(judgeCase));
        }
        if (judgeConfig != null) {
            question.setJudgeConfig(JSONUtil.toJsonStr(judgeConfig));
        }
        questionService.validQuestion(question, true);
        User loginUser = userFeignClient.getLoginUser(request);
        question.setUserId(loginUser.getId());
        question.setFavourNum(0);
        question.setThumbNum(0);
        boolean result = questionService.save(question);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        long newQuestionId = question.getId();
        return ResultUtils.success(newQuestionId);
    }

    /**
     * 删除
     *
     * @param deleteRequest
     * @param request
     * @return
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteQuestion(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = userFeignClient.getLoginUser(request);
        long id = deleteRequest.getId();
        boolean b = questionService.deleteQuestionAndSubmit(id, user);
        return ResultUtils.success(b);
    }

    /**
     * 更新（仅管理员）
     *
     * @param questionUpdateRequest
     * @return
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateQuestion(@RequestBody QuestionUpdateRequest questionUpdateRequest) {
        if (questionUpdateRequest == null || questionUpdateRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Question question = new Question();
        BeanUtils.copyProperties(questionUpdateRequest, question);
        List<String> tags = questionUpdateRequest.getTags();
        if (tags != null) {
            question.setTags(JSONUtil.toJsonStr(tags));
        }
        Integer difficulty = questionUpdateRequest.getDifficulty();
        if (QuestionDifficultyEnum.getEnumByValue(difficulty) != null) {
            question.setDifficulty(difficulty);
        }
        List<JudgeCase> judgeCase = questionUpdateRequest.getJudgeCase();
        JudgeConfig judgeConfig = questionUpdateRequest.getJudgeConfig();
        if (judgeCase != null) {
            question.setJudgeCase(JSONUtil.toJsonStr(judgeCase));
        }
        if (judgeConfig != null) {
            question.setJudgeConfig(JSONUtil.toJsonStr(judgeConfig));
        }
        // 参数校验
        questionService.validQuestion(question, false);
        long id = questionUpdateRequest.getId();
        // 判断是否存在
        Question oldQuestion = questionService.getById(id);
        ThrowUtils.throwIf(oldQuestion == null, ErrorCode.NOT_FOUND_ERROR);
        boolean result = questionService.updateById(question);
        return ResultUtils.success(result);
    }

    /**
     * 根据 id 获取
     *
     * @param id
     * @return
     */
    @GetMapping("/get/vo")
    public BaseResponse<QuestionVO> getQuestionVOById(long id, HttpServletRequest request) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userFeignClient.getLoginUser(request);
        //检测和处置爬虫
        crawlerDetect(loginUser.getId(),request);
        Question question = questionService.getById(id);
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        return ResultUtils.success(questionService.getQuestionVO(question, loginUser));
    }

    /**
     * 根据 id 获取
     * 管理员专属
     *
     * @param id
     * @return
     */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Question> getQuestionById(long id) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Question question = questionService.getById(id);
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        return ResultUtils.success(question);
    }

    /**
     * 分页获取列表（仅管理员）
     *
     * @param questionQueryRequest
     * @return
     */
    @PostMapping("/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<Question>> listQuestionByPage(@RequestBody QuestionQueryRequest questionQueryRequest) {
        long current = questionQueryRequest.getCurrent();
        long size = questionQueryRequest.getPageSize();
        Page<Question> questionPage = questionService.page(new Page<>(current, size),
                questionService.getQueryWrapper(questionQueryRequest));
        return ResultUtils.success(questionPage);
    }

    /**
     * 分页获取列表（封装类）
     *
     * @param questionQueryRequest
     * @param request
     * @return
     */
    @PostMapping("/list/page/vo")
    public BaseResponse<Page<QuestionVO>> listQuestionVOByPage(@RequestBody QuestionQueryRequest questionQueryRequest,
                                                               HttpServletRequest request) {
        long current = questionQueryRequest.getCurrent();
        long size = questionQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
        Page<Question> questionPage = questionService.page(new Page<>(current, size),
                questionService.getQueryWrapper(questionQueryRequest));
        return ResultUtils.success(questionService.getQuestionVOPage(questionPage, request));
    }

    /**
     * 分页获取当前用户创建的资源列表
     *
     * @param questionQueryRequest
     * @param request
     * @return
     */
    @PostMapping("/my/list/page/vo")
    public BaseResponse<Page<QuestionVO>> listMyQuestionVOByPage(@RequestBody QuestionQueryRequest questionQueryRequest,
                                                                 HttpServletRequest request) {
        if (questionQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userFeignClient.getLoginUser(request);
        questionQueryRequest.setUserId(loginUser.getId());
        long current = questionQueryRequest.getCurrent();
        long size = questionQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
        Page<Question> questionPage = questionService.page(new Page<>(current, size),
                questionService.getQueryWrapper(questionQueryRequest));
        return ResultUtils.success(questionService.getQuestionVOPage(questionPage, request));
    }

    /**
     * 编辑（用户）
     *
     * @param questionEditRequest
     * @param request
     * @return
     */
    @PostMapping("/edit")
    public BaseResponse<Boolean> editQuestion(@RequestBody QuestionEditRequest questionEditRequest, HttpServletRequest request) {
        if (questionEditRequest == null || questionEditRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Question question = new Question();
        BeanUtils.copyProperties(questionEditRequest, question);
        List<String> tags = questionEditRequest.getTags();
        if (tags != null) {
            question.setTags(JSONUtil.toJsonStr(tags));
        }
        Integer difficulty = questionEditRequest.getDifficulty();
        if (QuestionDifficultyEnum.getEnumByValue(difficulty) != null) {
            question.setDifficulty(difficulty);
        }
        List<JudgeCase> judgeCase = questionEditRequest.getJudgeCase();
        JudgeConfig judgeConfig = questionEditRequest.getJudgeConfig();
        if (judgeCase != null) {
            question.setJudgeCase(JSONUtil.toJsonStr(judgeCase));
        }
        if (judgeConfig != null) {
            question.setJudgeConfig(JSONUtil.toJsonStr(judgeConfig));
        }
        // 参数校验
        questionService.validQuestion(question, false);
        User loginUser = userFeignClient.getLoginUser(request);
        long id = questionEditRequest.getId();
        // 判断是否存在
        Question oldQuestion = questionService.getById(id);
        ThrowUtils.throwIf(oldQuestion == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人或管理员可编辑
        if (!oldQuestion.getUserId().equals(loginUser.getId()) && !userFeignClient.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        boolean result = questionService.updateById(question);
        return ResultUtils.success(result);
    }
//    /**
//     *  通过questionId返回对应的答案
//     */
//    @GetMapping("/answer/{questionId}")
//    public BaseResponse<String> getAnswerByQuestionId(@PathVariable("questionId") Long questionId){
//        if (questionId == null || questionId <= 0) {
//            throw new BusinessException(ErrorCode.PARAMS_ERROR,"questionId不能为空");
//        }
//        Question question = questionService.getById(questionId);
//        if (question == null) {
//            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,"题目不存在");
//        }
//        return ResultUtils.success(question.getAnswer());
//    }

    /**
     * 提交代码
     *
     * @param questionSubmitAddRequest
     * @param request
     * @return resultNum
     */
    @PostMapping("/question_submit/do")
    public BaseResponse<Long> doQuestionSubmit(@RequestBody QuestionSubmitAddRequest questionSubmitAddRequest,
                                               HttpServletRequest request) {
        String userId = request.getHeader("X-user-Id");
        try(Entry entry = SphU.entry("questionSubmit", EntryType.IN, 1, userId)) {
            if (questionSubmitAddRequest == null || questionSubmitAddRequest.getQuestionId() <= 0) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR);
            }
            // 登录才能提交代码
            final User loginUser = userFeignClient.getLoginUser(request);
            long questionSubmitId = questionSubmitService.doQuestionSubmit(questionSubmitAddRequest, loginUser);
            //更新数据库然后删除缓存
            log.info("删除缓存");
            clearUserCache(loginUser.getId());
            clearLeaderboardCache();
            clearHotQuestionsCache();
            return ResultUtils.success(questionSubmitId);
        }catch (BlockException e){
//            MailUtil.send("3105755134@qq.com", "提交代码限流告警", "->傻逼用户"+userId+"频繁提交代码",false);
            throw new BusinessException(ErrorCode.TOO_MANY_REQUEST);
        }
    }

//    @GetMapping(value = "/ai-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
//    public Flux<String> aiQuestionStream(
//            @RequestParam("questionId") Long questionId,
//            @RequestParam("language") String language,
//            HttpServletRequest request) {
//
//        // 参数校验和用户登录验证
//        if (questionId == null || questionId <= 0) {
//            return Flux.error(new BusinessException(ErrorCode.PARAMS_ERROR));
//        }
////        User loginUser = userFeignClient.getLoginUser(request);
////        if (loginUser == null) {
////            return Flux.error(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));
////        }
//
//        // 获取题目信息
//        Question question = questionService.getById(questionId);
//        if (question == null) {
//            return Flux.error(new BusinessException(ErrorCode.NOT_FOUND_ERROR));
//        }
//        // 直接获取AI原始流
//        return aiManager.getGenResultStream(
//                question.getTitle(),
//                question.getContent(),
//                language
//        );
//    }

    /**
     * 分页获取列表（仅管理员）
     *
     * @param questionSubmitQueryRequest
     * @return
     */
    @PostMapping("/question_submit/list/page")
    public BaseResponse<Page<QuestionSubmitVO>> listQuestionSubmitByPage(@RequestBody QuestionSubmitQueryRequest questionSubmitQueryRequest, HttpServletRequest request) {
        long current = questionSubmitQueryRequest.getCurrent();
        long size = questionSubmitQueryRequest.getPageSize();
        User loginUser = userFeignClient.getLoginUser(request);
        Page<QuestionSubmit> questionSubmitPage = questionSubmitService.page(new Page<>(current, size),
                questionSubmitService.getQueryWrapper(questionSubmitQueryRequest));
        return ResultUtils.success(questionSubmitService.getQuestionSubmitVOPage(questionSubmitPage, loginUser));
    }

    /**
     * 分页获取题目提交列表（用户自己）
     *
     * @param questionSubmitQueryRequest
     * @return
     */
    @PostMapping("/question_submit/list/page/my")
    public BaseResponse<Page<MyQuestionSubmitVO>> listQuestionMySubmitByPage(@RequestBody QuestionSubmitQueryRequest questionSubmitQueryRequest, HttpServletRequest request) {
        long current = questionSubmitQueryRequest.getCurrent();
        long size = questionSubmitQueryRequest.getPageSize();
        User loginUser = userFeignClient.getLoginUser(request);
        questionSubmitQueryRequest.setUserId(loginUser.getId());
        // 尝试从缓存获取
        String cacheKey = RedisConstant.USER_SUBMITS + loginUser.getId();
        Page<MyQuestionSubmitVO> cachedSubmits = (Page<MyQuestionSubmitVO>) redisTemplate.opsForValue().get(cacheKey);
        if (cachedSubmits != null) {
            return ResultUtils.success(cachedSubmits);
        }
        // 缓存未命中，重新计算
        Page<QuestionSubmit> questionSubmitPage = questionSubmitService.page(new Page<>(current, size),
                questionSubmitService.getQueryWrapper(questionSubmitQueryRequest));
        Page<MyQuestionSubmitVO> result = questionSubmitService.getMyQuestionSubmitVOPage(questionSubmitPage, loginUser);
        if (result.getRecords().isEmpty()) {
            redisTemplate.opsForValue().set(cacheKey, result, 5, TimeUnit.MINUTES); // 空结果缓存5分钟
        } else {
            redisTemplate.opsForValue().set(cacheKey, result, 1, TimeUnit.HOURS); // 正常结果缓存1小时
        }
        return ResultUtils.success(result);
    }

    /**
     * 统计个人数据
     *
     * @param request
     * @return
     */
    @GetMapping("/status")
    public BaseResponse<UserStatsVO> getUserStats(HttpServletRequest request) {
        User loginUser = userFeignClient.getLoginUser(request);
        //先从缓存获取
        String cacheKey = RedisConstant.USER_STATS + loginUser.getId();
        UserStatsVO cachedUserStatsVO = (UserStatsVO) redisTemplate.opsForValue().get(cacheKey);
        if (cachedUserStatsVO!=null){
            return ResultUtils.success(cachedUserStatsVO);
        }
        UserStatsVO userStatsVO = questionSubmitService.getUserStats(loginUser.getId());
        //设置缓存，过期时间1小时
        redisTemplate.opsForValue().set(cacheKey, userStatsVO, 1, TimeUnit.HOURS);
        return ResultUtils.success(userStatsVO);
    }

    @GetMapping("/leaderboard")
    public BaseResponse<List<UserLeaderboardVO>> getLeaderboard() {
        // 调用questionSubmitService的getLeaderboard方法获取排行榜
        // 尝试从缓存获取
        String cacheKey = RedisConstant.LEADERBOARD;
        List<UserLeaderboardVO> cachedLeaderboard = (List<UserLeaderboardVO>) redisTemplate.opsForValue().get(cacheKey);
        if (cachedLeaderboard != null) {
            return ResultUtils.success(cachedLeaderboard);
        }
        List<UserLeaderboardVO> leaderboardVOS = questionSubmitService.getLeaderboard();
        // 设置缓存，过期时间1小时
        redisTemplate.opsForValue().set(cacheKey, leaderboardVOS, 1, TimeUnit.HOURS);
        return ResultUtils.success(leaderboardVOS);
    }

    /**
     * 返回近日热题列表
     *
     * @param questionQueryRequest
     * @param request
     * @return
     */
    @PostMapping("/hot/list")
    public BaseResponse<Page<HotQuestionVO>> getHotQuestionSubmitList(@RequestBody QuestionQueryRequest questionQueryRequest, HttpServletRequest request) {
        User loginUser = userFeignClient.getLoginUser(request);
        questionQueryRequest.setUserId(loginUser.getId());
        // 尝试从缓存获取
        String cacheKey = RedisConstant.HOT_QUESTIONS;
        Page<HotQuestionVO> cachedHotQuestionVO = (Page<HotQuestionVO>) redisTemplate.opsForValue().get(cacheKey);
        if (cachedHotQuestionVO != null) {
            return ResultUtils.success(cachedHotQuestionVO);
        }
        Page<HotQuestionVO> questionVOPage = questionService.listHotQuestions(questionQueryRequest);
        // 设置缓存，过期时间1小时
        redisTemplate.opsForValue().set(cacheKey, questionVOPage, 1, TimeUnit.HOURS);
        return ResultUtils.success(questionVOPage);
    }

    /**
     * 清除用户相关的缓存
     */
    private void clearUserCache(Long userId) {
        String[] keys = new String[]{
                RedisConstant.USER_STATS + userId,
                RedisConstant.USER_SUBMITS + userId
        };
        // 使用延迟双删策略，延迟500ms
        cacheClearTask.delayedDoubleDelete(keys, 2000);
    }

    /**
     * 清除排行榜缓存
     */
    private void clearLeaderboardCache() {
        String[] keys = new String[]{RedisConstant.LEADERBOARD};
        // 使用延迟双删策略，延迟500ms
        cacheClearTask.delayedDoubleDelete(keys, 500);
    }

    /**
     * 清除热门题目缓存
     */
    private void clearHotQuestionsCache() {
        String[] keys = new String[]{RedisConstant.HOT_QUESTIONS};
        // 使用延迟双删策略，延迟500ms
        cacheClearTask.delayedDoubleDelete(keys, 500);
    }

    /**
     * 检测爬虫
     *
     * @param loginUserId
     */
    private void crawlerDetect(long loginUserId,HttpServletRequest request) {
        // 调用多少次时告警
        final int WARN_COUNT = 10;
        // 超过多少次封号
        final int BAN_COUNT = 20;
        // 拼接访问 key
        String key = String.format("user:access:%s", loginUserId);
        // 一分钟内访问次数，180 秒过期
        long count = countManager.incrAndGetCounter(key, 1, TimeUnit.MINUTES, 180);
        // 是否封号
        if (count > BAN_COUNT) {
            // 踢下线
            String token = request.getHeader("Authorization");
            userFeignClient.logout(token);
            // 封号
            User updateUser = new User();
            updateUser.setId(loginUserId);
            updateUser.setUserRole("ban");
            userFeignClient.updateById(updateUser);
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "访问太频繁，已被封号");
        }
        // 是否告警
        if (count == WARN_COUNT) {
            // 可以改为向管理员发送邮件通知
            MailUtil.send("3105755134@qq.com", "爬虫告警", "->傻逼用户"+loginUserId+"疑似爬虫",false);
            throw new BusinessException(110, "警告访问太频繁");
        }
    }
}
