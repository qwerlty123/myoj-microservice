package com.qwerlty.myojbackendcommentservice.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.qwerlty.myojbackendmodel.model.dto.comment.CommentAddRequest;
import com.qwerlty.myojbackendmodel.model.entity.Comment;
import com.qwerlty.myojbackendmodel.model.entity.User;
import com.qwerlty.myojbackendmodel.model.vo.CommentVO;

import java.util.List;

/**
* @author ybb
* @description 针对表【comment(评论表)】的数据库操作Service
* @createDate 2024-12-19 23:56:18
*/
public interface CommentService extends IService<Comment> {

    Long addComment(CommentAddRequest commentAddRequest);

    Boolean deleteComment(Comment comment, User loginUser);

    List<CommentVO> listCommentsByQuestionId(Long questionId);

    /**
     * 获取问题的评论列表（分页）
     * @param questionId 问题ID
     * @param current 当前页
     * @param pageSize 页大小
     * @param sortType 排序类型
     * @param userId 用户id
     * @return 评论列表
     */
    Page<CommentVO> listQuestionComments(long questionId, long current, long pageSize, String sortType,Long userId);
    /**
     * 获取评论的回复列表
     * @param commentId 评论ID
     * @return 回复列表
     */
    List<CommentVO> listCommentReplies(long commentId);
    /**
     * 点赞评论
     * @param commentId 评论ID
     * @param userId 用户ID
     * @return 是否成功
     */
    boolean likeComment(long commentId, long userId);
    /**
     * 删除评论
     * @param commentId 评论ID
     * @param userId 用户ID
     * @return 是否成功
     */
    boolean removeComment(long commentId, long userId);
}
