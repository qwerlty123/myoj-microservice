---
docId: ce-type-mismatch
title: 类型不匹配与泛型编译错误
topic: types
language: common
errorType: COMPILE_ERROR
source: MyOJ Knowledge Base
---
# 类型不匹配

容器元素类型、函数返回类型和赋值目标必须一致。泛型报错很长时，先找最内层的实际类型与期望类型，不要只看最后一行。字面量也有默认类型，混合运算可能触发提升或窄化限制。

检查 API 重载选择是否符合预期，尤其是数值、字符、字符串和集合之间。对无法推断的泛型参数，可把链式调用拆开并显式声明中间变量类型，以定位首次不兼容的位置。
