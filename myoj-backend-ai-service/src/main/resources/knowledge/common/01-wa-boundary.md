---
docId: wa-boundary
title: Wrong Answer 边界条件检查
topic: boundary
language: common
errorType: WRONG_ANSWER
source: MyOJ Knowledge Base
---
# 边界条件检查

WA 且整体思路看似正确时，先枚举输入规模的最小值、最大值、空集合、单元素、全部相等、严格递增和严格递减。检查循环端点是开区间还是闭区间，数组长度与最后一个合法下标不要混用。

对二分、滑动窗口和前缀和，分别写出区间语义，例如 `[left,right)`，并逐行确认移动后语义仍成立。不要猜隐藏用例；可以用手工构造的小输入追踪关键变量，比较“期望状态”和“程序状态”第一次分叉的位置。
