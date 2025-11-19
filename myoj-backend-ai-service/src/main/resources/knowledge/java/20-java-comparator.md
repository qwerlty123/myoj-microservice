---
docId: java-comparator-contract
title: Java Comparator 契约
topic: sorting
language: java
errorType: WRONG_ANSWER
source: MyOJ Knowledge Base
---
# Comparator 契约

Comparator 对相等元素必须返回 0，并保持反对称和传递性。用减法比较可能溢出，混用多个条件时容易出现循环顺序。优先使用 comparing、thenComparing 或显式的 Integer.compare/Long.compare。

TreeSet 和 TreeMap 会把比较结果为 0 的元素视为同一键，因此比较规则必须包含题意要求的唯一性字段。排序前列出几组相等和极值样例验证规则。
