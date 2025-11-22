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
    answer       mediumtext                           null comment '题目答案',
    submitNum    int          default 0                not null comment '题目提交数',
    acceptedNum  int          default 0                not null comment '题目通过数',
    judgeCase    mediumtext                           null comment '判题用例（json 数组）',
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
    judgeAttempt int        default 1                not null comment '当前判题执行代次',
    questionId bigint                               not null comment '题目 id',
    userId     bigint                               not null comment '提交用户 id',
    retryCount int          default 0                not null comment '判题执行重试次数',
    lastError  varchar(1024)                        null comment '最近一次错误',
    nextRetryTime datetime                           null comment '下一次判题执行调度时间',
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
    id               bigint                                not null comment 'id' primary key,
    questionSubmitId bigint                                not null comment '提交 id',
    eventId          varchar(64)                           not null comment '稳定事件 id',
    eventType        varchar(64)   default 'JUDGE_REQUESTED' not null comment '事件类型',
    schemaVersion    int           default 1                not null comment '消息模式版本',
    judgeAttempt     int           default 1                not null comment '判题执行代次',
    payload          varchar(1024)                          not null comment '消息 JSON',
    status           tinyint       default 0                not null comment '状态（0-待投递 1-已投递 2-死信 3-投递中）',
    retryCount       int           default 0                not null comment '投递重试次数',
    nextRetryTime    datetime      default CURRENT_TIMESTAMP not null comment '下一次重试时间',
    lastError        varchar(1024)                          null comment '最近一次投递错误',
    lockToken        varchar(64)                            null comment '投递租约令牌',
    leaseUntil       datetime                               null comment '投递租约到期时间',
    createTime       datetime      default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime       datetime      default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    unique index uk_eventId (eventId),
    unique index uk_submitAttemptEvent (questionSubmitId, judgeAttempt, eventType),
    index idx_submitId (questionSubmitId),
    index idx_status_nextRetryTime (status, nextRetryTime),
    index idx_status_leaseUntil (status, leaseUntil)
) comment '判题投递外盒' collate = utf8mb4_unicode_ci;

-- AI 提交复盘业务记录；创建和重试直接写 Redis Stream，不作为本地消息表使用
create table if not exists ai_feedback_task
(
    id               bigint                                   not null comment 'id' primary key,
    requestKey       char(64)                                 not null comment 'SHA-256 幂等键',
    userId           bigint                                   not null comment '用户 id',
    submissionId     bigint                                   not null comment '提交 id',
    questionId       bigint                                   not null comment '题目 id',
    status           tinyint        default 0                 not null comment '0待执行 1执行中 2成功 3失败 4超时',
    resultJson       json                                     null comment '完整结构化复盘结果（含引用）',
    modelName        varchar(128)                              not null comment '模型名称',
    promptVersion    varchar(64)                               not null comment 'Prompt 版本',
    knowledgeVersion varchar(64)                               not null comment '知识库版本',
    inputTokens      int unsigned    default 0                 not null comment '输入 Token',
    outputTokens     int unsigned    default 0                 not null comment '输出 Token',
    latencyMs        bigint unsigned default 0                 not null comment '模型调用耗时',
    attemptCount     smallint unsigned default 0               not null comment '实际执行次数',
    startedTime      datetime                                  null comment '最近开始执行时间',
    finishedTime     datetime                                  null comment '终态完成时间',
    errorCode        varchar(64)                               null comment '错误码',
    lastError        varchar(512)                              null comment '最近错误摘要',
    createTime       datetime        default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime       datetime        default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    unique index uk_requestKey (requestKey),
    index idx_status_startedTime (status, startedTime),
    index idx_user_submission_time (userId, submissionId, createTime),
    index idx_createTime (createTime)
) comment 'AI 提交复盘任务' collate = utf8mb4_unicode_ci;

-- AI 自动出题任务；生成草稿不会直接写入 question 表
create table if not exists ai_problem_generation_task
(
    id             bigint                                   not null comment '任务 id' primary key,
    requestKey     char(64)                                 not null comment '用户幂等键哈希',
    userId         bigint                                   not null comment '管理员用户 id',
    mode           varchar(32)                              not null comment 'FULL_PROBLEM / TEST_CASES',
    status         tinyint        default 0                 not null comment '0待执行 1执行中 2待审核 3失败 4超时 5取消',
    stage          varchar(64)    default 'QUEUED'          not null,
    progress       tinyint unsigned default 0               not null,
    requestJson    json                                     not null,
    resultJson     json                                     null,
    validationJson json                                     null,
    modelName      varchar(128)                              not null,
    promptVersion  varchar(64)                               not null,
    inputTokens    int unsigned    default 0                 not null,
    outputTokens   int unsigned    default 0                 not null,
    latencyMs      bigint unsigned default 0                 not null,
    attemptCount   smallint unsigned default 0               not null,
    cancelRequested tinyint       default 0                 not null,
    startedTime    datetime                                  null,
    finishedTime   datetime                                  null,
    errorCode      varchar(64)                               null,
    lastError      varchar(512)                              null,
    createTime     datetime        default CURRENT_TIMESTAMP not null,
    updateTime     datetime        default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    version        int unsigned    default 0                 not null,
    unique index uk_generation_requestKey (requestKey),
    index idx_generation_user_time (userId, createTime),
    index idx_generation_status_time (status, updateTime)
) comment 'AI 题目生成任务' collate = utf8mb4_unicode_ci;

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
