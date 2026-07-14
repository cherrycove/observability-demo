# 故障场景目录

公开控制接口只有：

- `POST /api/demo/faults/{scenarioId}/enable`
- `POST /api/demo/faults/off`
- `POST /api/demo/warmup`

三者都必须携带 `X-Demo-Control-Token`。后端故障有 TTL，并可显式恢复。

| scenarioId | 层级 | 目标 | 预期观察 |
| --- | --- | --- | --- |
| `frontend_click_error` | 前端 | Browser | RUM Error、Browser Log、用户行为 |
| `frontend_slow_resource` | 前端 | Browser | 慢 Resource 与页面体验 |
| `frontend_sourcemap_error` | 前端 | 压缩 JS | SourceMap 还原源码与行号 |
| `order_slow` | 服务 | order-service | 入口慢 Span、接口延迟 |
| `inventory_redis_timeout` | 依赖 | Redis | 依赖超时、错误 Span、关联日志 |
| `payment_slow` | 服务 | payment-service | 慢 Span、慢方法与 Profile |
| `payment_error` | 服务 | payment-service | HTTP 5xx、错误率和失败 Trace |
| `payment_cpu_burn` | JVM | payment-service | CPU、JVM 与 Profile 热点 |

示例：

```bash
export DEMO_CONTROL_TOKEN='你的控制口令'
scripts/inject-fault.sh inventory_redis_timeout
scripts/generate-traffic.sh
scripts/inject-fault.sh off
```

内部 `/admin/fault/**` 由 order-service 调用。Gateway 对外返回 404，且 chart 不为内部服务创建外部入口。
