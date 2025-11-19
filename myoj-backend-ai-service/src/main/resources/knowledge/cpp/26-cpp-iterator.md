---
docId: cpp-iterator-invalidation
title: C++ 迭代器与引用失效
topic: collections
language: cpp
errorType: RUNTIME_ERROR
source: MyOJ Knowledge Base
---
# 迭代器失效

vector 扩容、erase 和 insert 可能使迭代器、引用或指针失效；不同容器的规则不同。修改容器后继续使用旧位置会导致崩溃或错误结果。

使用 erase 返回的新迭代器继续遍历，或先记录索引并在修改后重新获取引用。reserve 只能在容量上界可知时减少扩容，并不能改变其他失效规则。
