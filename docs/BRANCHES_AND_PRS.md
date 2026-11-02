# 分支与 PR 导航

更新：2026-10-27。共享前端只保留一条开发分支，不再按移植功能堆叠分支。

## 日常使用

| 分支 | 用途 |
| --- | --- |
| `main` | 跟随 [上游 main](https://github.com/yydhYYDH/ClawMate-Android/tree/main)，本轮核对为 `13708b4`；不直接堆入待评审功能 |
| `codex/shared-frontend` | 唯一共享前端开发入口；继续完成技术路线验证与移植，阶段与结果见迁移日志 |

后续相关移植直接在共享开发分支累积小提交并推送 fork；只有独立且可能破坏稳定性的试验才另开临时分支。
用户决定先完成技术路线闭环，再与 fellow 讨论是否递交 PR；在收到新指示前不新建、重开或自动合并上游 PR。

## 评审入口

此前的[上游 #5](https://github.com/yydhYYDH/ClawMate-Android/pull/5) 已按用户最新决定关闭，保留历史记录。原 fork Draft #3～7 也已关闭，不作为当前评审请求。上游 Compose [#4](https://github.com/yydhYYDH/ClawMate-Android/pull/4) 已合并。

共享分支已合入上游 `13708b4`，后续改动继续在该分支维护。不改变正式应用包名或迁移用户数据。
此前 PR 的无冲突检查与 Auto-merge 权限拒绝只属于历史验证；当前不申请合并。

## 历史追溯

清理前 13 条功能分支及完整提交号保存在 [分支归档](archive/BRANCHES_2026-10-27.json)。
原始共享提交由标签 `archive/shared-before-consolidation-20261027` 保留，Compose 评审父链由 `archive/compose-review-20261027` 保留。
删除的只是被替代的分支引用，不删除提交或历史 PR。本地与 fork 远端均仅保留上述两条日常分支。
