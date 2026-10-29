# Harness 质量报告

数据：`docs/demo/metrics.jsonl`，21 条事件，2026-10-29 13:22 ~ 2026-10-29 13:23

| 指标 | 值 |
|---|---|
| Agent 会话数 | 6 |
| Stop 一次通过率 | 40% |
| 平均修复轮数（首次通过前被拦次数） | 0.6 |
| 早层拦截占比（L0+L1 / 全部） | 70% |
| 升级到人工 | 1 |

## 各层拦截

| 层 | 名称 | 次数 |
|---|---|---|
| L0 | 执行前守卫 | 5 |
| L1 | 编辑后即时检查 | 2 |
| L2 | 结束前增量检查 | 3 |
| L3 | 提交 / CI | 0 |

## 拦截原因 Top

| 类别 | 原因 | 次数 |
|---|---|---|
| L2 verify | mvn test (services/user) | 2 |
| L0 守卫 | migration-immutable | 1 |
| L0 守卫 | generated-file | 1 |
| L0 守卫 | no-verify | 1 |
| L0 守卫 | force-push | 1 |
| L0 守卫 | destructive-sql | 1 |
| L1 编辑后 | compile | 1 |
| L1 编辑后 | migration | 1 |
| L2 verify | contract-diff | 1 |

## 检查耗时（中位数）

| 检查 | ms |
|---|---|
| L1 compile | 3769 |
| L1 migration | 276 |
| L1 speclint | 340 |
| L2 stop | 10887 |

## Agent 会话

| 会话 | Stop 检查次数 | 首次即通过 | 修复轮数 | 最终通过 |
|---|---|---|---|---|
| add-user-phone | 1 | 是 | 0 | 是 |
| cleanup-shell | 0 | – | 0 | – |
| flaky-fix | 1 | 否 | 1 | 否 |
| order-currency | 2 | 否 | 1 | 是 |
| refactor-controller | 1 | 是 | 0 | 是 |
| rename-email | 2 | 否 | 1 | 是 |
