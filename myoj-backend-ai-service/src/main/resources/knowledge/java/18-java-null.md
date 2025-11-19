---
docId: java-null-unboxing
title: Java 空指针与自动拆箱
topic: null
language: java
errorType: RUNTIME_ERROR
source: MyOJ Knowledge Base
---
# Java 空指针与拆箱

包装类型为 null 时，赋给基本类型、参与算术或条件判断会触发自动拆箱并抛出空指针异常。Map.get 返回 null 既可能表示键不存在，也可能表示值本身为空。

结合堆栈定位解引用表达式，将链式访问拆开。使用 containsKey、getOrDefault 或显式初始化要符合业务语义；不要用随意默认值掩盖漏初始化。数组元素和对象字段也会有默认空值。
