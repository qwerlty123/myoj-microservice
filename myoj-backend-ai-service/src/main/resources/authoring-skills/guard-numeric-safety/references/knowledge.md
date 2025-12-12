---
docId: authoring-numeric-safety
title: 数值题的溢出与精度风险
topic: numeric-safety
language: common
errorType: AUTHORING
source: MyOJ Authoring Skills
audience: authoring
---
# 数值安全风险

先界定中间量，再界定最终答案。常见风险包括 `a * b` 在赋给长整型前已按较窄类型溢出、前缀和超过单个元素范围、`(left + right) / 2` 的加法溢出、最小负数取绝对值、最小公倍数先乘后除，以及多次加减模后仍为负数。

浮点题要同时定义数学误差和文本输出。检查绝对误差、相对误差或固定小数位究竟由谁保证；构造接近舍入分界、数量级悬殊、误差累积和结果接近零的输入。能够整数化或使用有理数表达时，优先让 oracle 使用精确表示。

数值对抗用例应隔离风险：用小输入证明操作顺序错误，用接近上界的输入证明类型选择错误，用分界两侧的成对输入证明比较或舍入错误。
