---
docId: tle-complexity
title: 时间复杂度与数据规模
topic: complexity
language: common
errorType: TLE
source: MyOJ Knowledge Base
---
# 复杂度估算

先从最大数据规模反推可接受数量级，再分析最坏情况而非平均样例。嵌套循环不一定是平方复杂度，关键在于内层指针是否单调；看似单层循环也可能因排序、字符串复制或容器操作隐藏高成本。

标出每个主要步骤的执行次数和单次成本。若复杂度确实过高，应寻找重复计算、可维护状态、前缀信息、哈希索引或更合适的数据结构，而不是只做常数级微优化。
