---
docId: cpp-integer-overflow
title: C++ 整数类型与溢出
topic: numeric
language: cpp
errorType: WRONG_ANSWER
source: MyOJ Knowledge Base
---
# C++ 整数范围

表达式类型由操作数决定，接收变量是 long long 也不能挽救已经按 int 计算的乘积。使用 `1LL` 等方式在运算前提升类型，并注意 size_t 与负数混合比较。

有符号溢出属于未定义行为。计算中点可用避免两端直接相加的形式。位运算时区分有符号和无符号右移，并确认移位数小于类型位宽。
