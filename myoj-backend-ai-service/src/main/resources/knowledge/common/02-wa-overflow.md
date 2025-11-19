---
docId: wa-integer-overflow
title: 整数溢出导致的错误答案
topic: numeric
language: common
errorType: WRONG_ANSWER
source: MyOJ Knowledge Base
---
# 整数溢出

中间结果的范围可能远大于最终答案。加法、乘法、平方、前缀和、距离差和比较器减法都要先估算上界；变量最终存入长整型，不代表表达式会自动用长整型计算。

取模题要在每次可能越界前使用足够宽的类型，并留意负数取模。浮点计算不要直接用相等比较，误差容忍应结合题目精度。排查时记录参与运算的类型与理论最大绝对值，而不只观察样例输出。
