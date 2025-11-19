---
docId: cpp-undefined-behavior
title: C++ 未定义行为排查
topic: runtime
language: cpp
errorType: RUNTIME_ERROR
source: MyOJ Knowledge Base
---
# C++ 未定义行为

越界访问、使用失效引用、读取未初始化变量、非法移位和有符号溢出都可能产生不稳定结果。程序在本地“恰好能跑”不能证明行为合法。

从崩溃点反查对象生命周期和索引范围，检查 vector 扩容后保存的指针或迭代器。不要依赖编译器、优化级别或内存布局的偶然表现；应恢复语言标准保证的前置条件。
