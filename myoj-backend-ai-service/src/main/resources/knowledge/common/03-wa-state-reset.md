---
docId: wa-state-reset
title: 多测试用例状态污染
topic: state
language: common
errorType: WRONG_ANSWER
source: MyOJ Knowledge Base
---
# 状态重置

多组测试数据之间的全局数组、计数器、答案缓存、访问标记和容器必须按题意重置。只重置“用过的范围”时，要保证记录范围本身没有遗漏；复用对象时也要清空其内部状态。

若单组运行正确而合并两组后失败，可将第二组单独运行，再交换两组顺序。输出依赖前一组时通常是状态污染。递归搜索还要确认回溯路径上每次修改都有对称恢复。
