---
docId: mle-allocation
title: 内存上界估算
topic: memory
language: common
errorType: MLE
source: MyOJ Knowledge Base
---
# 内存估算

按最大规模估算数组元素数乘以单元素字节数，并考虑对象头、引用、容器扩容和运行时开销。二维对象数组、字符串集合和哈希表的真实占用通常显著高于原始数据大小。

寻找是否可以滚动数组、状态压缩、原地处理或只保留必要窗口。不要同时保留多份等价数据。递归深度也会占用栈空间；缓存没有淘汰条件时可能随状态数持续增长。
