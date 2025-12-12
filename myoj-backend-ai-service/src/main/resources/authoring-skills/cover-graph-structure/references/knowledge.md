---
docId: authoring-graph-structure
title: 图论题的结构风险与覆盖
topic: graph-structure
language: common
errorType: AUTHORING
source: MyOJ Authoring Skills
audience: authoring
---
# 图结构风险

图题首先核对模型：有向或无向、带权或无权、是否连通、是否允许重边与自环、点编号范围、不可达输出。树和 DAG 的承诺必须由 validator 验证；否则参考解会把输入假设当成事实。

高价值结构包括单点、多个连通分量、长链、星形、环、菱形汇合、多个等价最短路、稠密子图和孤立目标。带权图还要覆盖零权、相同权、极大权、负权是否合法以及路径和的类型范围。

常见可信错误包括只从一个起点遍历、把有向边当无向边、访问标记时机错误、并查集初始化或合并数量错误、拓扑排序漏检环、Dijkstra 在负权上使用，以及递归 DFS 在长链上耗尽栈。
