---
docId: java-concurrent-modification
title: Java 遍历时修改集合
topic: collections
language: java
errorType: RUNTIME_ERROR
source: MyOJ Knowledge Base
---
# 遍历时修改集合

增强 for 循环或 Iterator 遍历期间直接通过集合增删，可能触发 ConcurrentModificationException；这里的 concurrent 不一定涉及多线程。需要删除当前元素时使用迭代器支持的方法，或先收集变更后统一应用。

若必须在遍历中扩展搜索队列，选择允许该语义的数据结构，并证明不会无限加入。复制集合再遍历会增加内存，需要结合规模判断。
