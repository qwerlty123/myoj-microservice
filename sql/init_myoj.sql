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

-- AI 多轮题目辅导（完整定义亦见 migration_20260819_ai_chat.sql）
create table if not exists ai_chat_session
(
    id bigint auto_increment primary key,
    userId bigint not null,
    questionId bigint not null,
    mode varchar(16) default 'normal' not null,
    status tinyint default 0 not null comment '0 active, 1 archived, 2 disabled',
    disableReason varchar(512) null,
    lastMessageTime datetime default CURRENT_TIMESTAMP not null,
    expireTime datetime not null,
    createTime datetime default CURRENT_TIMESTAMP not null,
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    isDelete tinyint default 0 not null,
    unique index uk_ai_chat_user_question (userId, questionId, isDelete),
    index idx_ai_chat_expire (status, expireTime),
    index idx_ai_chat_question (questionId)
) comment 'AI question tutoring session' collate = utf8mb4_unicode_ci;

create table if not exists ai_chat_message
(
    id bigint auto_increment primary key,
    sessionId bigint not null,
    role varchar(16) not null,
    mode varchar(16) default 'normal' not null,
    content longtext not null,
    toolEvents longtext null,
    violation tinyint default 0 not null,
    createTime datetime default CURRENT_TIMESTAMP not null,
    isDelete tinyint default 0 not null,
    index idx_ai_chat_message_session (sessionId, id)
) comment 'AI tutoring message history' collate = utf8mb4_unicode_ci;

create table if not exists ai_prompt_config
(
    id bigint auto_increment primary key,
    scene varchar(32) not null,
    versionNo int default 1 not null,
    promptContent longtext not null,
    enabled tinyint default 1 not null,
    isActive tinyint default 1 not null,
    createTime datetime default CURRENT_TIMESTAMP not null,
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    isDelete tinyint default 0 not null,
    unique index uk_ai_prompt_version (scene, versionNo, isDelete)
) comment 'AI prompt configuration' collate = utf8mb4_unicode_ci;

create table if not exists ai_model_config
(
    id bigint auto_increment primary key,
    modelName varchar(128) not null,
    enabled tinyint default 1 not null,
    isDefault tinyint default 0 not null,
    createTime datetime default CURRENT_TIMESTAMP not null,
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    isDelete tinyint default 0 not null,
    index idx_ai_model_active (enabled, isDefault, isDelete)
) comment 'AI model selection configuration' collate = utf8mb4_unicode_ci;

create table if not exists ai_disable_rule
(
    id bigint auto_increment primary key,
    scopeType varchar(32) not null comment 'global, user, question',
    scopeId bigint null,
    reason varchar(512) not null,
    startTime datetime null,
    endTime datetime null,
    enabled tinyint default 1 not null,
    createTime datetime default CURRENT_TIMESTAMP not null,
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    isDelete tinyint default 0 not null,
    index idx_ai_disable_active (enabled, startTime, endTime),
    index idx_ai_disable_scope (scopeType, scopeId)
) comment 'AI availability rules' collate = utf8mb4_unicode_ci;

create table if not exists ai_sensitive_word
(
    id bigint auto_increment primary key,
    word varchar(255) not null,
    enabled tinyint default 1 not null,
    createTime datetime default CURRENT_TIMESTAMP not null,
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    isDelete tinyint default 0 not null,
    unique index uk_ai_sensitive_word (word, isDelete)
) comment 'AI input sensitive words' collate = utf8mb4_unicode_ci;

create table if not exists ai_violation_log
(
    id bigint auto_increment primary key,
    userId bigint not null,
    sessionId bigint not null,
    messageId bigint null,
    violationType varchar(64) not null,
    content varchar(1000) null,
    createTime datetime default CURRENT_TIMESTAMP not null,
    isDelete tinyint default 0 not null,
    index idx_ai_violation_user (userId, createTime),
    index idx_ai_violation_session (sessionId)
) comment 'AI policy violation audit log' collate = utf8mb4_unicode_ci;

create table if not exists ai_tool_config
(
    id bigint auto_increment primary key,
    toolName varchar(64) not null,
    enabled tinyint default 1 not null,
    dailyLimit int default 30 not null,
    createTime datetime default CURRENT_TIMESTAMP not null,
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    isDelete tinyint default 0 not null,
    unique index uk_ai_tool_name (toolName, isDelete)
) comment 'AI Agent tool policy' collate = utf8mb4_unicode_ci;

create table if not exists ai_tool_call_log
(
    id bigint auto_increment primary key,
    userId bigint not null,
    sessionId bigint not null,
    toolName varchar(64) not null,
    success tinyint not null,
    resultSummary varchar(1000) null,
    createTime datetime default CURRENT_TIMESTAMP not null,
    isDelete tinyint default 0 not null,
    index idx_ai_tool_daily (userId, toolName, createTime),
    index idx_ai_tool_session (sessionId)
) comment 'AI Agent tool call audit log' collate = utf8mb4_unicode_ci;

insert ignore into ai_prompt_config (scene, versionNo, promptContent, enabled, isActive)
values ('normal', 1,
        '你是 MyOJ 的算法题辅导助手。结合题面、用户代码、判题结果和历史对话回答。优先引导用户定位问题，除非明确要求，不直接给完整标准答案。',
        1, 1),
       ('agent', 1,
        '你是 MyOJ 的算法题智能辅导 Agent。必要时使用工具分析提交、构造测试、分析报错或检索公开资料，并基于真实工具结果给出结论。',
        1, 1);

insert ignore into ai_tool_config (toolName, enabled, dailyLimit)
values ('searchWeb', 1, 20),
       ('submission_analysis', 1, 50),
       ('testcase_generator', 1, 50),
       ('sample_error_analyzer', 1, 50),
       ('run_user_code', 1, 20);
