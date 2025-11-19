---
docId: re-index-out-of-range
title: 数组越界诊断
topic: array
language: common
errorType: RUNTIME_ERROR
source: MyOJ Knowledge Base
---
# 数组越界

合法下标范围是从零到长度减一。优先检查循环终止条件、由输入换算得到的下标、前后邻居访问以及空数组。二维数组要分别验证行列，并确认它是否规则矩阵。

不要只在访问前把下标强行截断，这会改变算法语义。应追踪下标的来源、理论范围和它为何越界。二分查找、前缀数组和哨兵数组尤其容易因为长度多一或少一产生偏移。
