---
docId: tle-io
title: 大输入输出性能
topic: io
language: common
errorType: TLE
source: MyOJ Knowledge Base
---
# 输入输出性能

数据量很大时，逐字符的高开销解析、频繁刷新输出、循环中反复拼接不可变字符串都会成为瓶颈。应使用缓冲输入、批量输出，并避免为每个 token 创建不必要的临时对象。

先判断算法复杂度是否达标，再优化 I/O；错误的数量级不能靠快读挽救。输出很多时将内容写入可变缓冲区，最后统一写出，同时注意缓冲区本身的内存上限。
