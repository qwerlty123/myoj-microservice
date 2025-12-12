---
docId: authoring-sequence-boundaries
title: 序列、窗口与区间的边界覆盖
topic: sequence-boundaries
language: common
errorType: AUTHORING
source: MyOJ Authoring Skills
audience: authoring
---
# 序列结构覆盖

序列题的高价值用例来自形状。至少考虑长度一、全部相等、严格递增、严格递减、周期交替、重复值聚集、有效元素位于首尾，以及答案覆盖整个序列或为空。滑动窗口要覆盖左端连续收缩、右端连续扩张、窗口在边界处首次满足和始终不满足。

区间题先固定 `[l,r]`、`[l,r)` 等语义，再构造相离、端点接触、部分重叠、完全包含、完全相同和逆序输入。矩阵题覆盖单行、单列、方阵、狭长矩阵、边角目标和内部目标。

对抗用例应针对可信错误：遗漏最后一个元素、把长度当下标、排序后丢失原位置、错误合并相邻区间、重复值导致指针不前进，或把字符数与字节数混用。
