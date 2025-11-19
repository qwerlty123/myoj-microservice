---
docId: java-recursion-stack
title: Java 递归栈溢出
topic: recursion
language: java
errorType: RUNTIME_ERROR
source: MyOJ Knowledge Base
---
# 递归栈

深度接近输入规模的 DFS、链表处理或退化递归可能导致 StackOverflowError。先确认递归出口覆盖全部分支，并检查参数是否每次向出口推进。

当理论深度很大时，使用显式栈改写迭代通常更稳妥。仅调整 JVM 栈大小不适合在线判题，也不能修复无限递归。显式栈中要保存递归帧真正需要的状态。
