---
docId: tle-loop-progress
title: 死循环与循环进度
topic: loop
language: common
errorType: TLE
source: MyOJ Knowledge Base
---
# 循环进度

每轮循环都应有可证明的进度量，例如区间长度缩小、指针前进或未处理元素减少。检查 continue 分支是否跳过了更新，二分边界是否可能原地不动，队列处理是否会把同一状态无限加入。

用极小输入手工记录连续几轮的状态元组。如果状态重复且没有新的外部信息，通常已经形成环。修复时明确不变量和终止条件，避免仅增加任意迭代上限来隐藏问题。
