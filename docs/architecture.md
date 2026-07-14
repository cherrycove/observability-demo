# 架构与数据流

Demo 由四个 Spring Boot 3.5.15 服务、MySQL、Redis 和浏览器商城组成。Gateway 是唯一公开入口；它拒绝 `/admin/**`，其余请求代理给 order-service。order-service 编排订单落库、库存预留和支付，内部故障接口只通过 ClusterIP 或 Compose 内部网络调用。

一次正常购买的调用顺序是：

1. 商城生成 `X-Key-Request` 与 `X-Business-Request-Id`。
2. gateway-service 透传业务头和分布式追踪上下文。
3. order-service 写入 MySQL，再调用 inventory-service。
4. inventory-service 访问 Redis 并返回预留结果。
5. order-service 调用 payment-service，最后更新订单状态。
6. order-service 的日志查看器按受限格式的业务 ID 查询近期容器日志。

Java Agent 固定为观测云扩展版 `v1.55.10-ext`，由版本化官方 init 镜像提供并在构建时校验 SHA256。Kubernetes 中 Java Pod 从 `status.hostIP` 取得节点地址，将 DDTrace、Profile 和 JMX/StatsD 分别发送到 `9529`、`9529` 和 `8125`。

DataKit 作为独立 Release 部署。应用 chart 不创建 DataKit、不保存 DataWay URL，也不添加会阻断节点采集的 NetworkPolicy。MySQL、Redis 与全部业务负载都是 Demo 级单副本。
