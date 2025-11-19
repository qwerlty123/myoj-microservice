---
docId: python-performance
title: Python 常见性能陷阱
topic: complexity
language: python
errorType: TLE
source: MyOJ Knowledge Base
---
# Python 性能

列表头部删除、循环中的字符串累加、在线性容器中反复查找、深层函数调用都可能放大开销。根据操作语义选择 deque、set、字典或列表，并把不会变化的计算移出循环。

使用 sys.stdin.buffer 处理大量输入，输出可收集后一次写出。先修正复杂度，再考虑局部变量绑定等微优化；不要用难以维护的技巧掩盖算法瓶颈。
