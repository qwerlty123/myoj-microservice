---
docId: mle-container-growth
title: 容器无限增长与重复状态
topic: memory
language: common
errorType: MLE
source: MyOJ Knowledge Base
---
# 容器增长

BFS、搜索和事件模拟中，队列或集合快速增长常源于重复状态未去重、去重时机太晚或状态编码不唯一。通常应在入队时标记，而不是出队后才标记。

检查缓存键是否包含了无关维度，或漏掉会导致碰撞的关键维度。若状态空间理论上就过大，需要改变表示或算法；只在达到阈值时清空容器会破坏正确性。
