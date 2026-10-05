# Discovery page / 服务名单页

Evidence opened before designing `GET /services`. These are the pages that were read, not a copied console.

- Spring Cloud Consul registers host, port, id, name, and tags, and by default asks Consul to call `/actuator/health` every 10 seconds; a failed check marks the instance critical. That document also lists many extra knobs: health path, interval, headers, TTL heartbeat, metadata, and instance id. https://docs.spring.io/spring-cloud-consul/reference/discovery.html
- Consul's service UI at `/ui/services` has shown "All service checks passing" on each instance while the HTTP API reported a critical check (`TTL expired`). After the false passing text improved, the topology tab opened first instead of Instances, which operators called annoying. https://github.com/hashicorp/consul/issues/22690
- Netflix calls the Eureka 1.0 dashboard very rudimentary, and describes the discontinued Eureka 2.0 dashboard as a richer view of registry internals, server health, subscriptions, and audit logs. https://github.com/Netflix/eureka/wiki/Eureka-2.0-Motivations
- Eureka's dashboard can still list an instance as `UP` when its last renewal was two days earlier and its health URL could not be opened, because self-preservation keeps the stale instance. https://stackoverflow.com/questions/46304112/why-eureka-considers-a-service-still-up-even-if-its-last-renewal-time-was-two
- The Nacos console guide describes a service table of name, cluster count, instance count, healthy-instance count, and a details button, then weight edits, metadata edits, and online/offline buttons. https://nacos.io/en/docs/latest/console-guide/
- A Nacos maintainer turned down another grouping level on that console: too much hierarchy makes the page very complex (层级太多会导致控制台页面非常复杂). https://github.com/alibaba/nacos/issues/7963
- An early Nacos console note asked for services with zero healthy instances to be highlighted, and for a required Group field to be pulled out of Advanced Options: hidden knobs and a crowded page are the same complaint. https://github.com/alibaba/nacos/issues/142

`GET /services` is the opposite of that complaint. One sentence says what the page is. Each row is a service name, an address, and one status word. The word is `up` only when a short TCP connect succeeds; otherwise it is `unknown`. `GET /registry/services` uses that same word, so the page cannot say passing while the list says critical. There is no weight, metadata editor, namespace, topology tab, or online/offline control.

`GET /services` 对着的就是这个抱怨。开头一句话说明这页是什么。每一行是服务名、地址和一个状态词。只有短 TCP 连接成功才是 `up`，否则是 `unknown`。`GET /registry/services` 用同一个词，页面不能说 passing 而名单说 critical。没有权重、元数据编辑、命名空间、拓扑页，也没有上下线开关。
