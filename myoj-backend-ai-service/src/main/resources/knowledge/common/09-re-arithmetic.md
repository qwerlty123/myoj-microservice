---
docId: re-arithmetic
title: 除零与非法算术操作
topic: numeric
language: common
errorType: RUNTIME_ERROR
source: MyOJ Knowledge Base
---
# 算术异常

除数可能由差值、计数或输入转换得到，不能只看样例中是否为零。整数除法还会截断小数，负数除法和取模规则也可能与推导假设不同。

在执行运算前写明除数必须满足的不变量，并检查所有分支能否保证它。若零是合法输入，应根据题意单独处理；若不合法，应查明为何计数或状态没有按预期更新，而不是返回任意默认值。
