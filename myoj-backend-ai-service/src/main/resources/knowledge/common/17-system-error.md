---
docId: system-error-boundary
title: 系统错误与用户代码错误的边界
topic: judge
language: common
errorType: SYSTEM_ERROR
source: MyOJ Knowledge Base
---
# 系统错误边界

判题系统错误不等价于用户算法错误。缺少编译器、沙箱不可用、内部通信失败或判题机资源异常时，不应虚构某一行代码是根因。应先建议重试并保留任务标识，便于服务端追踪。

若同时存在明确的用户代码异常信息，可以将其作为次要线索，但必须说明不确定性。系统错误分析不应推断隐藏输入，也不应给出与现有证据无关的题解。
