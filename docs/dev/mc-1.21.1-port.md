# GymCraft 1.3.0 的 Minecraft 1.21.1 移植

## 分支选择

本次使用 `codex/mc1211-1.3.0`，以主分支提交 `976e8d0` 及当前 1.3.0 发布准备改动为基线，在独立工作树开发。

旧 `adapt/1.21.1` 停在 `7c49f6d`（1.2.1 发行线）；它包含可复用的平台经验，但缺少新的空间系统、场景基线、组件参数和 LLM 包重构。直接在旧分支堆叠主分支变更会产生大量业务冲突，因此新分支保留最新业务逻辑，仅迁移平台适配。旧分支未修改。

## 平台差异

- 运行目标：Minecraft 1.21.1、NeoForge 21.1.250、Java 21、Gradle 9.2.1；两个 CI 工作流均使用 JDK 21。
- 资源位置使用 `ResourceLocation`，权限使用 `CommandSourceStack.hasPermission`；实体创建、伤害、移动控制与装备接口按 1.21.1 映射适配。
- 非 Minecraft 运行库通过 `libraries` 接入开发运行类路径，gRPC/protobuf 随模组内嵌，Guava/Gson 使用游戏提供的版本。
- 实体快照使用 1.21.1 的 `CompoundTag` 存取；`gym/compat` 提供 NBT 默认值、方块调色板、Profiler 区间和移动等待接口。
- 三个内置场景保持 v3 布局，但数据版本转为 3955；实体装备使用 `HandItems`/`ArmorItems`，属性使用旧版注册 ID，专属背包转为 ItemStackHandler 的 `Items` + `Size`。
- 客户端载荷处理器在载荷注册时装配，执行时进入客户端线程；专用服务器启动已验证不会提前加载客户端渲染类。
- 可视化使用 `RenderLevelStageEvent`、原版线框和字体接口，保留图层过滤、空间边界、路径、箭头、淡出、穿墙及标签。
- 1.21.1 缺少 SADDLE 装备槽：装备槽为 0–6，存储槽从 7 起；客户端应以菜单观测的 slot_id 为准。
- GameTest 改为注解式注册，保留主分支的全部 75 个显式注册用例；聊天窗口和结构注入仍分批隔离。

## 客户端与协议

`.proto` 和 Python 包源码与主分支逐文件比较一致。Python 继续使用 `gymcraft==1.3.0`，不发布 `+mc1.21.1` Python 版本。

同版本不同 MC 适配共用 gRPC 协议；菜单布局、可用物品和世界内容由服务端观测表达。场景中的实体 NBT 属于 Minecraft 平台数据，本次内置资产已转换，不能仅改文件名当作已完成移植。

## 验证结果

- `gradlew compileJava` 与 `gradlew build packagePython` 通过。
- 75/75 GameTest 通过，包括场景存档、基线恢复、区块票、采铁工具链、铁傀儡对抗监守者、导航、菜单、聊天和可视化协议。
- mypy：77 个源文件通过；Python 单元测试：45/45 通过。
- 启动 1.21.1 专用服务器，用同一 1.3.0 Python 客户端完成 `ListEnvironments`、`Connect`、带 `components` options 的 `Reset`、`Step` 和关闭会话；观测包含全部九类组件，动作空间为结构化 protobuf，info 返回字典。
- 临时专用服务器已正常关闭。测试记录位于工作树的 `build/port-gametest.log`、`build/port-python.log`、`build/port-smoke-result.json`。
- 客户端渲染接口已编译验证；尚未进行游戏客户端内的画面验收。

## 发布约定

- Release：`GymCraft 1.3.0+mc1.21.1`。
- Tag：`v1.3.0+mc1.21.1`。
- 模组附件：`gymcraft-1.3.0+mc1.21.1.jar`。
- Python：复用 PyPI `gymcraft==1.3.0`；工作流跳过已存在的分发文件。
