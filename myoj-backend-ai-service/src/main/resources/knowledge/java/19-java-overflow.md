---
docId: java-numeric-overflow
title: Java 数值提升与溢出
topic: numeric
language: java
errorType: WRONG_ANSWER
source: MyOJ Knowledge Base
---
# Java 数值运算

两个 int 相乘会先得到 int，之后赋给 long 已经无法恢复溢出的值。应让至少一个操作数在运算前成为 long。比较器不要写成 `a-b`，可使用类型对应的 compare 方法。

Math.abs 对最小负数仍可能溢出；移位位数和符号扩展也要检查。估算输入约束下每个中间表达式的范围，而不是只扩大最终答案变量。
