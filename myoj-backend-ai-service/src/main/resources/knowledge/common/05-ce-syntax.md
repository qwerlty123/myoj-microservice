---
docId: ce-syntax
title: 编译错误的最短定位路径
topic: compile
language: common
errorType: COMPILE_ERROR
source: MyOJ Knowledge Base
---
# 编译错误定位

从编译器报告的第一条错误开始处理，后续大量错误可能只是级联结果。先核对语言版本、入口函数或主类名称、括号与分号、变量作用域、函数参数数量和返回类型。

错误行不一定是根因行，缺失的括号或引号常出现在它之前。把复杂表达式拆成有明确类型的中间变量，可更快暴露类型推断问题。不要为了消除报错随意强制转换，应先确认数据含义和目标类型。
