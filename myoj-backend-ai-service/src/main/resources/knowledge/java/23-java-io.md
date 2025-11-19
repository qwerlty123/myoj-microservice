---
docId: java-fast-io
title: Java 大规模输入输出
topic: io
language: java
errorType: TLE
source: MyOJ Knowledge Base
---
# Java I/O

Scanner 解析方便但在大量 token 下开销较高。可使用 BufferedInputStream 自行解析，或 BufferedReader 配合合适的分词方式。输出使用 StringBuilder 或 BufferedWriter 批量写出，避免循环内频繁 flush。

性能优化前先验证算法数量级。字符串切分会创建许多对象，超大输入时应关注分配成本。自定义快读还必须正确处理负号、文件结束和空白字符。
