---
docId: ac-code-quality
title: AC 代码质量检查
topic: review
language: common
errorType: ACCEPTED
source: MyOJ Knowledge Base
---
# 代码质量

检查变量名是否表达角色，复杂分支能否拆成职责单一的函数，魔法数字是否来自题目约束，重复逻辑是否能安全复用。注释应解释原因和不变量，而不是逐字重复代码。

保留必要的边界防护，删除调试输出和无效状态。重构不能改变复杂度和数值范围。建议应定位到具体代码特征，并说明收益与代价，而不是泛泛要求“提高可读性”。
