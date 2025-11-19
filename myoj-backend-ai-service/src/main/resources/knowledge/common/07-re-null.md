---
docId: re-null-access
title: 空值访问与缺失数据
topic: null
language: common
errorType: RUNTIME_ERROR
source: MyOJ Knowledge Base
---
# 空值访问

运行时空值通常来自查找失败、空输入、未初始化分支或容器取不到键。沿异常栈定位首次解引用，再反向检查该值的所有赋值路径。不要在每处机械添加空判断，这可能掩盖真正的不变量被破坏。

区分“空值是合法状态”与“理论上不应为空”。前者应定义清晰的默认行为，后者应在更靠近数据来源的位置校验并保留上下文。链式调用可拆开以确定具体哪一步返回空值。
