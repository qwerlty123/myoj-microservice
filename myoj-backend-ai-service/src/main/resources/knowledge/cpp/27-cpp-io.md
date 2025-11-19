---
docId: cpp-fast-io
title: C++ 输入输出性能
topic: io
language: cpp
errorType: TLE
source: MyOJ Knowledge Base
---
# C++ I/O

大量输入时可关闭 iostream 与 stdio 的同步并解除 cin/cout 绑定。使用换行字符替代每次都会刷新缓冲区的 endl。不要在关闭同步后混用两套 I/O 而依赖未定义的顺序。

字符串拼接和格式化也可能成为热点，必要时预留容量并批量输出。但算法复杂度未达标时，快 I/O 只能改善常数。
