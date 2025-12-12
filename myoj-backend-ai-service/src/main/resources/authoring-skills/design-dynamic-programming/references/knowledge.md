---
docId: authoring-dynamic-programming
title: 动态规划状态与转移审查
topic: dynamic-programming
language: common
errorType: AUTHORING
source: MyOJ Authoring Skills
audience: authoring
---
# 动态规划审查

先用一句话定义 `dp[state]` 的数学含义，再检查初始化是否恰好覆盖最小合法状态、每个转移是否只读取已经确定的状态、所有合法方案是否至少被覆盖一次、非法或不可达状态是否保持独立哨兵。

背包类重点区分物品可用次数和容量循环方向；计数类重点区分方案是否考虑顺序、重复元素是否可区分、何时取模；区间 DP 重点覆盖长度一和分割点端点；树形 DP 重点覆盖父子方向和合并临时状态；滚动数组重点检查本轮写入是否污染仍需读取的旧状态。

高价值用例包括容量零、无法恰好达到、刚好达到、多个最优解、零价值或零代价是否合法、重复选择、负无穷哨兵参与运算，以及答案位于非默认最终状态。
