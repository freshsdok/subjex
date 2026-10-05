# Config page / 配置名单页

Evidence opened before designing `GET /config`. These are the pages and threads that were read, not a copied console.

- Spring Cloud Config publishes a JSON dump of stacked `propertySources` for `/{application}/{profile}`; the client `/env` view repeats that pile with origin strings such as a git file path and line number. There is still no first-party human list of “this key, this winning value, this plain source.” https://docs.spring.io/spring-cloud-config/docs/current/reference/html/quickstart.html
- Operators asked for a UI over Spring Cloud Config properties stored in Git or files; the replies point at building a custom endpoint because the server itself is not a readable list. https://stackoverflow.com/questions/48859937/im-looking-for-user-interface-or-spring-component-for-all-the-properties-that-a
- Nacos config history detail and rollback showed one Content/md5 field without saying whether it was the old or the new value for that moment; maintainers had to spell out old/new pairs. https://github.com/alibaba/nacos/issues/12925
- Nacos Next Console plugin detail now returns `configValueMetas.source` and `overridden` so operators can see which layer won (LOCAL_ONLY > RUNTIME_PERSISTED > STATIC > DEFAULT). That metadata exists because “which value wins” is easy to lose on a crowded page. https://nacos-group.github.io/en/docs/next/plugin/operations/
- Apollo documents associated namespaces that override public ones key-by-key (`k1=v3` wins over public `k1=v1` while `k2` stays public). The override story is real, and it is easy to misread which namespace supplied the effective value. https://github.com/apolloconfig/apollo/wiki/Apollo%E6%A0%B8%E5%BF%83%E6%A6%82%E5%BF%B5%E4%B9%8B%E2%80%9CNamespace%E2%80%9D
- Non-properties Apollo public namespaces cannot override a single key; the whole file wins or loses, which surprised callers who expected key-level override. https://github.com/apolloconfig/apollo/issues/4508

`GET /config` is the opposite of that complaint. One sentence says what the page is. Each row is a key, the effective value, and a plain-language source: local file or stored override. There is no giant property dump, no namespace tree, no editor, and no historical versions.

`GET /config` 对着的就是这个抱怨。开头一句话说明这页是什么。每一行是键、生效值，以及直白的来源：本地文件或已存覆盖。没有巨型属性堆、没有命名空间树、没有编辑器，也没有历史版本。
