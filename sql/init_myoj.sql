# 数据库初始化 - MyOJ 微服务版
# 与实体类 User / Question / QuestionSubmit 及帖子相关表一致

-- 创建库
create database if not exists myoj;

-- 切换库
use myoj;

-- 用户表（与 User 实体一致，id 由应用 ASSIGN_ID 生成）
create table if not exists user
(
    id           bigint                               not null comment 'id' primary key,
    userAccount  varchar(256)                         not null comment '账号',
    userPassword varchar(512)                         not null comment '密码',
    unionId      varchar(256)                         null comment '微信开放平台id',
    mpOpenId     varchar(256)                         null comment '公众号openId',
    userName     varchar(256)                         null comment '用户昵称',
    userAvatar   varchar(1024)                        null comment '用户头像',
    userProfile  varchar(512)                         null comment '用户简介',
    userRole     varchar(256) default 'user'          not null comment '用户角色：user/admin/ban',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint      default 0                not null comment '是否删除',
    index idx_unionId (unionId)
) comment '用户' collate = utf8mb4_unicode_ci;

-- 题目表（与 Question 实体一致，无 type 字段）
create table if not exists question
(
    id           bigint                               not null comment 'id' primary key,
    title        varchar(512)                        null comment '标题',
    content      text                                 null comment '内容',
    tags         varchar(1024)                       null comment '标签列表（json 数组）',
    answer       text                                 null comment '题目答案',
    submitNum    int          default 0                not null comment '题目提交数',
    acceptedNum  int          default 0                not null comment '题目通过数',
    judgeCase    text                                 null comment '判题用例（json 数组）',
    judgeConfig  text                                 null comment '判题配置（json 对象）',
    thumbNum     int          default 0                not null comment '点赞数',
    favourNum    int          default 0                not null comment '收藏数',
    userId       bigint                               not null comment '创建用户 id',
    difficulty   int                                  null comment '难度',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint      default 0                not null comment '是否删除',
    index idx_userId (userId)
) comment '题目' collate = utf8mb4_unicode_ci;

-- 题目提交表（与 QuestionSubmit 实体一致）
create table if not exists question_submit
(
    id         bigint                               not null comment 'id' primary key,
    language   varchar(128)                         not null comment '编程语言',
    code       longtext                             null comment '用户代码',
    judgeInfo  text                                 null comment '判题信息（json 对象）',
    status     int          default 0                not null comment '判题状态（0-待判题 1-判题中 2-成功 3-失败）',
    questionId bigint                               not null comment '题目 id',
    userId     bigint                               not null comment '提交用户 id',
    retryCount int          default 0                not null comment '重试次数',
    lastError  varchar(1024)                        null comment '最近一次错误',
    nextRetryTime datetime                           null comment '下一次重试时间',
    createTime datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete   tinyint      default 0                not null comment '是否删除',
    index idx_questionId (questionId),
    index idx_userId (userId),
    index idx_userId_status_questionId (userId, status, questionId)
) comment '题目提交' collate = utf8mb4_unicode_ci;

-- 判题 outbox（生产端可靠投递）
create table if not exists judge_task_outbox
(
    id            bigint                               not null comment 'id' primary key,
    questionSubmitId bigint                            not null comment '提交 id',
    payload       varchar(128)                         not null comment '消息体',
    status        tinyint      default 0                not null comment '状态（0-待投递 1-已投递 2-终止 3-投递中）',
    retryCount    int          default 0                not null comment '投递重试次数',
    nextRetryTime datetime     default CURRENT_TIMESTAMP not null comment '下一次重试时间',
    lastError     varchar(1024)                        null comment '最近一次投递错误',
    createTime    datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime    datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    index idx_submitId (questionSubmitId),
    index idx_status_nextRetryTime (status, nextRetryTime)
) comment '判题投递外盒' collate = utf8mb4_unicode_ci;

-- AI 提交分析任务，同时承担待投递消息的 Outbox 职责
create table if not exists ai_feedback_task
(
    id                 bigint                                not null comment 'id' primary key,
    requestKey         varchar(128)                          not null comment '幂等键',
    userId             bigint                                not null comment '用户 id',
    submissionId       bigint                                not null comment '提交 id',
    questionId         bigint                                not null comment '题目 id',
    status             tinyint       default 0               not null comment '0待投递 1投递中 2已入队 3执行中 4成功 5失败 6超时',
    resultJson         longtext                              null comment '结构化分析结果',
    citationsJson      text                                  null comment '知识库引用',
    modelName          varchar(128)                          null comment '模型名称',
    promptVersion      varchar(64)                           not null comment 'Prompt 版本',
    knowledgeVersion   varchar(64)                           not null comment '知识库版本',
    inputTokens        int            default 0               not null comment '输入 Token',
    outputTokens       int            default 0               not null comment '输出 Token',
    latencyMs          bigint         default 0               not null comment '模型调用耗时',
    dispatchRetryCount int            default 0               not null comment 'MQ 投递重试次数',
    executeRetryCount  int            default 0               not null comment '任务执行重试次数',
    nextRetryTime      datetime       default CURRENT_TIMESTAMP not null comment '下次重试时间',
    errorCode          varchar(64)                           null comment '错误码',
    lastError          varchar(1024)                         null comment '最近错误摘要',
    createTime         datetime       default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime         datetime       default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    unique index uk_requestKey (requestKey),
    index idx_status_nextRetryTime (status, nextRetryTime),
    index idx_userId_submissionId (userId, submissionId),
    index idx_createTime (createTime)
) comment 'AI 提交分析任务' collate = utf8mb4_unicode_ci;

-- 评论表（与 Comment 实体一致）
create table if not exists comment
(
    id          bigint auto_increment comment 'id' primary key,
    userId      bigint                             not null comment '发表评论的用户 id',
    questionId  bigint                             null comment '被评论的题目 id',
    content     text                               not null comment '评论内容',
    beCommentId bigint                             null comment '二级评论指向的一级评论 id',
    likeCount   int      default 0                 not null comment '点赞数',
    replyCount  int      default 0                 not null comment '回复数量',
    createTime  datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    isDelete    tinyint  default 0                 not null comment '是否删除',
    index idx_userId (userId),
    index idx_questionId (questionId),
    index idx_beCommentId (beCommentId),
    index idx_questionId_createTime (questionId, createTime DESC)
) comment '评论' collate = utf8mb4_unicode_ci;

-- 评论点赞表（与 CommentThumb 实体一致，硬删除）
create table if not exists comment_thumb
(
    id         bigint auto_increment comment 'id' primary key,
    commentId  bigint                             not null comment '评论 id',
    userId     bigint                             not null comment '用户 id',
    createTime datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    unique index uk_userId_commentId (userId, commentId)
) comment '评论点赞';

-- 帖子表
create table if not exists post
(
    id         bigint auto_increment comment 'id' primary key,
    title      varchar(512)                       null comment '标题',
    content    text                               null comment '内容',
    tags       varchar(1024)                      null comment '标签列表（json 数组）',
    thumbNum   int      default 0                 not null comment '点赞数',
    favourNum  int      default 0                 not null comment '收藏数',
    userId     bigint                             not null comment '创建用户 id',
    createTime datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete   tinyint  default 0                 not null comment '是否删除',
    index idx_userId (userId)
) comment '帖子' collate = utf8mb4_unicode_ci;

-- 帖子点赞表（硬删除）
create table if not exists post_thumb
(
    id         bigint auto_increment comment 'id' primary key,
    postId     bigint                             not null comment '帖子 id',
    userId     bigint                             not null comment '用户 id',
    createTime datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    index idx_postId (postId),
    index idx_userId (userId)
) comment '帖子点赞';

-- 帖子收藏表（硬删除）
create table if not exists post_favour
(
    id         bigint auto_increment comment 'id' primary key,
    postId     bigint                             not null comment '帖子 id',
    userId     bigint                             not null comment '用户 id',
    createTime datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    index idx_postId (postId),
    index idx_userId (userId)
) comment '帖子收藏';
