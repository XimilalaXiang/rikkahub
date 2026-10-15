# :rikkasync — 云同步引擎(纯 JVM)

端到端加密云同步的**平台无关引擎**:密码学(`Crypto`)、服务器客户端(`SyncClient`)、对账算法(`SyncEngine`)、
宿主契约(`HostAdapter`、`StateStore`)。与 TypeScript 参考实现(`rikkasync` 仓库 `core/`)逐段对应,
协议与算法的规格见该仓库的 `docs/02-protocol-v0.md`、`docs/03-engine-and-host.md`。

**本模块不依赖 Android,也不引用任何上游业务类型**——边界由编译器强制。Android 侧适配器放在 `app` 模块的 `sync/host` 包。
只使用工程里已有的依赖(OkHttp、kotlinx-serialization、coroutines)。

## 测试

```bash
# 密码学向量(不需要外部依赖)
./gradlew :rikkasync:test

# 引擎测试 + 与 TypeScript 引擎的互操作:需要 rikkasync 仓库的检出目录和 bun
RIKKASYNC_REPO=/path/to/rikkasync ./gradlew :rikkasync:test
```

没设置 `RIKKASYNC_REPO`(或没有 bun)时,依赖真实服务器的测试会被**跳过**(不是失败);向量测试始终运行。
`src/test/resources/crypto-v0.json` 必须与 rikkasync 仓库的 `vectors/crypto-v0.json` 逐字节一致(设置了 `RIKKASYNC_REPO` 时有测试校验)。

## 与 TypeScript 的差异(有意)

- 没有长驻循环 `run()`:Android 由 WorkManager + 前台触发调度(见 `docs/04` §7)。
- `HostAdapter` 的方法是 `suspend`,`StateStore` 保持同步。
