---
docId: python-recursion-depth
title: Python 递归深度问题
topic: recursion
language: python
errorType: RUNTIME_ERROR
source: MyOJ Knowledge Base
---
# Python 递归深度

线性深度 DFS 容易超过解释器递归限制。提高递归上限会同时增加栈风险，不能修复出口错误或无限递归。对大图和长链，显式栈或队列通常更可靠。

迭代改写时保存节点、父节点和处理阶段等必要信息，避免改变后序语义。还要在入栈时正确标记访问状态，防止同一节点反复进入。
