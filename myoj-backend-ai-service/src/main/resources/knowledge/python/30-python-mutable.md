---
docId: python-mutable-alias
title: Python 可变对象别名问题
topic: state
language: python
errorType: WRONG_ANSWER
source: MyOJ Knowledge Base
---
# 可变对象别名

使用乘法创建嵌套列表时，多个位置可能引用同一个内部列表；修改一个位置会同时影响其他位置。函数默认参数中的可变对象也会跨调用保留状态。

应为每个元素独立构造对象，并用不可变默认值再在函数内初始化。排查时比较对象身份和内容变化，确认复制是浅复制还是深复制；不必要的深复制则可能带来时间和内存问题。
