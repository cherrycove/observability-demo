# 可观测信号与字段

| 信号 | 来源 | 关键关联字段 |
| --- | --- | --- |
| Kubernetes/容器指标 | DataKit container、kubernetesprometheus | `project`、`cluster_name_k8s`、`pod_name`、`container_name` |
| APM Trace | Guance DDTrace Java Agent | `project`、`trace_id`、`span_id`、`service`、`env`、`version` |
| 应用日志 | stdout + `datakit/logs` Annotation | Trace 字段、业务字段、故障字段、进程/Pod/容器字段 |
| JVM 指标 | Java Agent → StatsD `8125` | `project`、`service`、`env`、JVM measurement |
| Profiling | Java Agent → Profile `9529` | `project`、`service`、`env`、`version` |
| RUM / Browser Logs / Replay | Browser SDK → `/rum-proxy` → DataKit | `project`、application、session、view、业务/故障上下文 |

统一产品标签为 `project=mall-demo`，由 DataKit global tag、Kubernetes label、Java Agent `DD_TAGS`、结构化日志与浏览器全局上下文共同设置。统一日志 `source` 为 `java_selfheal_demo`，Pipeline 为 `java-selfheal-demo.p`。应用输出以下字段：

- 产品：`project`。
- 业务：`key_request`、`biz_request_id`。
- 故障：`fault_id`、`fault_layer`、`fault_kind`、`fault_target`。
- 链路：`trace_id`、`span_id`、`service`、`env`、`version`。
- 运行身份：`process_id`、`host_process_id`、`container_process_id`、`host`、`host_name`、`pod_name`、`pod_namespace`、`container_name`、`container_id`。

`GET /api/demo/logs` 只接受应用生成的 `biz-...` 和 `ord-...` 格式，防止公开接口被用来用任意字符串扫描命名空间日志。只有 order-service 使用可读取 `pods` 与 `pods/log` 的 ServiceAccount；其他 Java Pod 不挂载 API token。
