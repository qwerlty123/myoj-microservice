---
docId: wa-ordering
title: 排序与比较规则错误
topic: sorting
language: common
errorType: WRONG_ANSWER
source: MyOJ Knowledge Base
---
# 排序与比较

比较规则必须满足自反、反对称和传递性；相等元素应返回相等，而不是强行决定先后。多关键字排序要明确主次字段和升降序，避免通过相减比较造成溢出。

贪心算法常把“排序实现错误”伪装成“策略错误”。先用三到五个元素列出期望顺序，再检查比较器对任意两项的结果。若算法依赖稳定排序，必须确认所用 API 的稳定性或显式加入次关键字。
