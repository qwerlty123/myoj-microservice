package com.qwerlty.myojbackendcommentservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qwerlty.myojbackendcommentservice.mapper.CommentMapper;
import com.qwerlty.myojbackendcommentservice.mapper.CommentThumbMapper;
import com.qwerlty.myojbackendcommentservice.service.CommentService;
import com.qwerlty.myojbackendcommon.common.ErrorCode;
import com.qwerlty.myojbackendcommon.exception.BusinessException;
import com.qwerlty.myojbackendmodel.model.dto.comment.CommentAddRequest;
import com.qwerlty.myojbackendmodel.model.entity.Comment;
import com.qwerlty.myojbackendmodel.model.entity.CommentThumb;
import com.qwerlty.myojbackendmodel.model.entity.User;
import com.qwerlty.myojbackendmodel.model.vo.CommentVO;
import com.qwerlty.myojbackendmodel.model.vo.UserVO;
import com.qwerlty.myojbackendserviceclient.client.UserFeignClient;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author ybb
 * @description 针对表【comment(评论表)】的数据库操作Service实现
 * @createDate 2024-12-19 23:56:18
 */
@Service
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment>
        implements CommentService {
    @Resource
    private UserFeignClient userFeignClient;

    @Resource
    private CommentThumbMapper commentThumbMapper;

    @Override
    public Long addComment(CommentAddRequest commentAddRequest) {
        Long userId = commentAddRequest.getUserId();
        Long questionId = commentAddRequest.getQuestionId();
        String content = commentAddRequest.getContent();
        Long beCommentId = commentAddRequest.getBeCommentId();
        if (content == null || content.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "评论内容不能为空");
        }
        if (content.length() > 100) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "评论内容长度不合法");
        }
        Comment comment = new Comment();
        comment.setUserId(userId);
        comment.setQuestionId(questionId);
        comment.setContent(content);
        comment.setBeCommentId(beCommentId);
        boolean save = this.save(comment);
        if (!save) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "评论失败");
        }
        // 如果是回复评论，沿 beCommentId 链向上追溯根父评论，更新回复数
        if (comment.getBeCommentId() != null) {
            Long rootParentId = findRootParentIdByChain(comment.getBeCommentId());
            Comment rootParent = this.getById(rootParentId);
            if (rootParent != null) {
                rootParent.setReplyCount(rootParent.getReplyCount() + 1);
                this.updateById(rootParent);
            }
        }
        return comment.getId();
    }

    /**
     * 沿 beCommentId 链向上逐条查询，找到最顶层的根父评论 ID。
     * 评论嵌套通常不超过 3 层，最多 2-3 次主键查询，替代原来加载全量评论建 Map 的方式。
     */
    private Long findRootParentIdByChain(Long commentId) {
        Long currentId = commentId;
        for (int depth = 0; depth < 50; depth++) {
            Comment c = this.getById(currentId);
            if (c == null || c.getBeCommentId() == null) {
                return currentId;
            }
            currentId = c.getBeCommentId();
        }
        return currentId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteComment(Comment delcomment, User loginUser) {
        Long commentId = delcomment.getId();
        Long questionId = delcomment.getQuestionId();
        // 1. 获取要删除的评论
        Comment comment = this.getById(commentId);
        if (comment == null) {
            return false;
        }

        // 2. 一次查询 + 内存 BFS 找出所有子孙评论 ID
        Set<Long> toDeleteIds = findAllDescendantIds(commentId, questionId);
        toDeleteIds.add(commentId);
        // 3. 删除评论本身（逻辑删除）
        return this.removeByIds(toDeleteIds);
    }

    @Override
    public List<CommentVO> listCommentsByQuestionId(Long questionId) {
        // 1. 获取该问题下的所有评论
        QueryWrapper<Comment> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("questionId", questionId);
        queryWrapper.eq("isDelete", 0);
        queryWrapper.orderByDesc("createTime");
        List<Comment> commentList = this.list(queryWrapper);

        if (CollectionUtils.isEmpty(commentList)) {
            return new ArrayList<>();
        }

        // 2. 获取所有评论用户id
        Set<Long> userIds = commentList.stream()
                .map(Comment::getUserId)
                .collect(Collectors.toSet());

        // 3. 获取用户信息
        Map<Long, UserVO> userVOMap = userFeignClient.listByIds(userIds).stream()
                .map(user -> userFeignClient.getUserVO(user)
                )
                .collect(Collectors.toMap(UserVO::getId, userVO -> userVO));

        // 4. 转换成 VO
        List<CommentVO> commentVOList = commentList.stream()
                .map(comment -> {
                    CommentVO commentVO = new CommentVO();
                    BeanUtils.copyProperties(comment, commentVO);
                    // 设置用户信息
                    commentVO.setUserVO(userVOMap.get(comment.getUserId()));
                    return commentVO;
                })
                .collect(Collectors.toList());

        // 5. 构建评论树
        return buildCommentTree(commentVOList);
    }

    /**
     * 构建评论树
     */
    private List<CommentVO> buildCommentTree(List<CommentVO> commentVOList) {
        // 1. 创建 id -> 评论 的映射，方便查找
        Map<Long, CommentVO> commentMap = commentVOList.stream()
                .collect(Collectors.toMap(CommentVO::getId, commentVO -> commentVO));

        // 2. 存储所有顶级评论
        List<CommentVO> rootComments = new ArrayList<>();

        // 3. 遍历所有评论，将其放入对应父评论的 children 中
        for (CommentVO commentVO : commentVOList) {
            Long beCommentId = commentVO.getBeCommentId();
            if (beCommentId == null) {
                // 顶级评论
                rootComments.add(commentVO);
            } else {
                // 子评论，找到父评论，加入其 children 列表
                CommentVO parentComment = commentMap.get(beCommentId);
                if (parentComment != null) {
                    if (parentComment.getChildren() == null) {
                        parentComment.setChildren(new ArrayList<>());
                    }
                    parentComment.getChildren().add(commentVO);
                }
            }
        }

        // 4. 对所有评论的 children 进行排序（按创建时间倒序）
        sortCommentChildren(rootComments);

        return rootComments;
    }

    /**
     * 递归对评论的子评论进行排序
     */
    private void sortCommentChildren(List<CommentVO> commentVOList) {
        if (CollectionUtils.isEmpty(commentVOList)) {
            return;
        }

        for (CommentVO commentVO : commentVOList) {
            if (commentVO.getChildren() != null) {
                // 按创建时间倒序排序
                commentVO.setChildren(commentVO.getChildren().stream()
                        .sorted((a, b) -> b.getCreateTime().compareTo(a.getCreateTime()))
                        .collect(Collectors.toList()));
                // 递归排序子评论的子评论
                sortCommentChildren(commentVO.getChildren());
            }
        }
    }

    /**
     * 一次查询该题目所有评论的 id 和 beCommentId（只查两列，不拉 content 等大字段），
     * 在内存中 BFS 找出指定评论的所有后代 ID，替代原来每层递归都查一次数据库的 N+1 方式。
     */
    private Set<Long> findAllDescendantIds(Long commentId, Long questionId) {
        List<Comment> allComments = this.list(new QueryWrapper<Comment>()
                .eq("questionId", questionId)
                .eq("isDelete", 0)
                .select("id", "beCommentId"));

        Map<Long, List<Long>> parentToChildren = new HashMap<>();
        for (Comment c : allComments) {
            if (c.getBeCommentId() != null) {
                parentToChildren.computeIfAbsent(c.getBeCommentId(), k -> new ArrayList<>()).add(c.getId());
            }
        }

        Set<Long> descendants = new HashSet<>();
        Queue<Long> queue = new LinkedList<>();
        queue.add(commentId);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            List<Long> children = parentToChildren.get(current);
            if (children != null) {
                for (Long childId : children) {
                    if (descendants.add(childId)) {
                        queue.add(childId);
                    }
                }
            }
        }
        return descendants;
    }

    @Override
    public Page<CommentVO> listQuestionComments(long questionId, long current, long pageSize, String sortType,Long userId) {
        // 构建查询条件
        QueryWrapper<Comment> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("questionId", questionId)
                .isNull("beCommentId");

        // 设置排序
        if ("hot".equals(sortType)) {
            queryWrapper.orderByDesc("likeCount", "createTime");
        } else {
            queryWrapper.orderByDesc("createTime");
        }

        // 执行分页查询
        List<Comment> commentList = this.list(new QueryWrapper<Comment>().eq("questionId", questionId));
        if (commentList.isEmpty()) {
            return new Page<>();
        }
        // 2. 获取所有评论用户的信息
        Set<Long> userIds = commentList.stream()
                .map(Comment::getUserId)
                .collect(Collectors.toSet());
        Map<Long, UserVO> userVOMap = userFeignClient.listByIds(userIds).stream()
                .map(user -> userFeignClient.getUserVO(user))
                .collect(Collectors.toMap(UserVO::getId, userVO -> userVO));

        // 2. 已登录，获取用户点赞状态
        Map<Long, Boolean> commentIdHasThumbMap = new HashMap<>();
        //获取关于这道题的commentIdSet集合
        Set<Long> commentSet = commentList.stream().map(Comment::getId).collect(Collectors.toSet());
        // 获取点赞
        QueryWrapper<CommentThumb> commentThumbQueryWrapper = new QueryWrapper<>();
        commentThumbQueryWrapper.in("commentId", commentSet);
        commentThumbQueryWrapper.eq("userId", userId);
        List<CommentThumb> commentCommentThumbList = commentThumbMapper.selectList(commentThumbQueryWrapper);
        commentCommentThumbList.forEach(commentCommentThumb -> commentIdHasThumbMap.put(commentCommentThumb.getCommentId(), true));
        // 3. 转换为VO并填充用户信息
        List<CommentVO> commentVOList = commentList.stream().map(reply -> {
            CommentVO replyVO = new CommentVO();
            BeanUtils.copyProperties(reply, replyVO);
            replyVO.setUserVO(userVOMap.get(reply.getUserId()));
            //设置用户是否点赞
            replyVO.setHasThumb(commentIdHasThumbMap.getOrDefault(reply.getId(), false));
            return replyVO;
        }).collect(Collectors.toList());
        List<CommentVO> commentVOS = buildCommentTree(commentVOList);
        //将commentVOS转成page
        Page<CommentVO> commentVOPage = new Page<>(current, pageSize);
        commentVOPage.setRecords(commentVOS);
        commentVOPage.setTotal(commentVOList.size());
        return commentVOPage;
    }

    @Override
    public List<CommentVO> listCommentReplies(long commentId) {
        List<Comment> replies = this.list(new QueryWrapper<Comment>()
                .eq("beCommentId", commentId)
                .eq("isDelete", 0)
                .orderByDesc("createTime"));

        if (CollectionUtils.isEmpty(replies)) {
            return Collections.emptyList();
        }

        Set<Long> userIds = replies.stream().map(Comment::getUserId).collect(Collectors.toSet());
        Map<Long, UserVO> userVOMap = userFeignClient.listByIds(userIds).stream()
                .map(user -> userFeignClient.getUserVO(user))
                .collect(Collectors.toMap(UserVO::getId, userVO -> userVO));

        return replies.stream().map(reply -> {
            CommentVO replyVO = new CommentVO();
            BeanUtils.copyProperties(reply, replyVO);
            replyVO.setUserVO(userVOMap.get(reply.getUserId()));
            return replyVO;
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean likeComment(long commentId, long userId) {
        Comment comment = this.getById(commentId);
        if (comment == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }

        // TODO: 这里可以添加点赞记录表，防止重复点赞
        comment.setLikeCount(comment.getLikeCount() + 1);
        return this.updateById(comment);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeComment(long commentId, long userId) {
        Comment comment = this.getById(commentId);
        if (comment == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }

        // 校验权限
        if (!comment.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        Long rootParentId = null;
        if (comment.getBeCommentId() != null) {
            rootParentId = findRootParentIdByChain(comment.getBeCommentId());
        }
        // 一次查询 + 内存 BFS 找出所有子孙评论 ID
        Set<Long> toDeleteIds = findAllDescendantIds(commentId, comment.getQuestionId());
        toDeleteIds.add(commentId);
        boolean success = this.removeByIds(toDeleteIds);
        // 更新原始父评论的回复数
        if (success && rootParentId != null) {
            Comment rootParent = this.getById(rootParentId);
            if (rootParent != null) {
                // 重新计算回复数
                long replyCount = this.count(new QueryWrapper<Comment>()
                        .eq("beCommentId", rootParentId));
                rootParent.setReplyCount((int) replyCount);
                this.updateById(rootParent);
            }
        }
        return success;
    }
}




