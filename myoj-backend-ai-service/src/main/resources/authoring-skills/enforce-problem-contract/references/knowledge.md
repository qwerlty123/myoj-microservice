---
docId: authoring-problem-contract
title: OJ 题目可执行契约检查
topic: authoring-contract
language: common
errorType: AUTHORING
source: MyOJ Authoring Skills
audience: authoring
---
# OJ 题目可执行契约

把题面看成解析器、求解器和判题器共同遵守的契约。逐项确认输入 token 的数量、分隔方式、范围、跨字段关系、多组数据终止条件和空白字符规则；逐项确认输出的顺序、精度、大小写、无解表示和换行要求。

数据范围同时承担算法约束。最大规模应让目标复杂度稳定通过，并让明显更慢的方案产生可观测差异。时间限制要为目标实现保留余量，内存限制要覆盖输入、主要数据结构、递归栈和语言运行时成本。

样例负责解释语义，不负责代替测试集。测试集按等价类、边界、最大规模和对抗结构覆盖；每个动态风险都应能描述一种可信错误实现以及区分它的输入性质。
