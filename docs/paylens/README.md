# PayLens 本地演示

PayLens 是基于 DaxPay 固定版本构建的求职演示扩展，展示“支付异常模拟 → 状态查询 → 证据时间线 → AI 辅助分析 → 人工处置”。它不接真实支付机构、不做资金清算，也不自动改变资金状态。

## 来源与改造

- 上游：[Dromara DaxPay](https://github.com/dromara/dax-pay)，本地固定在 `58c27954e64528226da3cc86b1338fdde6720cb3`。上游源码及原许可证保留在仓库，许可证为 LGPL-3.0-or-later。
- PayLens 的新增改动：`paylens-analysis` 中的 Spring AI ChatClient、异步分析与引用校验、本机 ONNX 向量模型；`paylens-incidents` 的确定性事件归并；`paylens-demo` 同源工作台、DaxPay 模拟通道、交易证据快照、人工拆分/合并审计和故障知识库。
- 模拟通道不会读取或保存真实通道密钥。模型默认使用 `mock`，配置云端模型后，只会发送脱敏后的交易证据和检索片段。

### DaxPay 技术背景

DaxPay 是面向多商户与支付服务场景的开源支付平台。PayLens 固定基于 DaxPay `4.0.0-beta7`（提交 `58c2795`），该版本后端使用 Java 25、Spring Boot 4.1.1，持久化与基础设施包括 PostgreSQL、Redis 和 Apache Artemis。支付核心覆盖下单、退款、回调、查单、同步和关单等交易流程，并提供运营端、商户端及权限管理能力。

其关键架构是“主应用编排 + 通道子应用适配”：主应用负责支付策略、通道路由和业务流程，通过声明式 HTTP 客户端调用独立部署的通道子应用；第三方支付 SDK 与各通道协议留在适配侧。上游还提供开放 API 签名、通道配置加密及管理端权限控制。PayLens 复用这些业务入口和交易数据，在其上增加异常事件分析工作台；支付渠道接入、订单状态机、原有认证和人工处置流程属于 DaxPay 上游能力，不应表述为 PayLens 从零实现。

技术背景以[锁定提交的源码与 README](https://github.com/dromara/dax-pay/tree/58c27954e64528226da3cc86b1338fdde6720cb3)和[上游部署说明](https://github.com/dromara/dax-pay/blob/58c27954e64528226da3cc86b1338fdde6720cb3/_doc/DEPLOYMENT.md)为准。使用与修改须遵守仓库所附 LGPL-3.0-or-later 许可证及相关协议。

### 简历项目描述（可直接改写使用）

**PayLens 支付异常事件工作台｜个人项目，基于 DaxPay 二次开发**

**技术栈：** Java 25、Spring Boot 4.1.1、Spring AI 2.0.1、PostgreSQL、Redis、Apache Artemis、ONNX Runtime、BGE-small-zh。

**项目介绍：** 基于开源支付平台 DaxPay 的支付订单、回调、查单和异常处理流程，扩展支付异常事件工作台。系统从业务记录生成可追溯证据时间线，使用规则边界与本机中文向量模型辅助归并相似异常，并调用云端大模型生成带证据和知识引用的排查建议；人工拆分、合并与支付处置继续复用 DaxPay 既有业务入口。

**个人工作：**

- 在 DaxPay 多模块 Spring Boot 工程中新增事件扫描、分析任务和工作台模块，保存批次输入快照、事件版本及人工调整审计记录。
- 使用 Spring AI 统一 Chat 与 Embedding 接口；通过 ONNX Runtime Java 本地运行 BGE 中文向量模型。模型不可用时退回规则归并，并将语义自动合并保持为默认关闭。
- 为 AI 分析加入异步任务、输入脱敏、JSON 与引用校验、失败重试及评测记录；模型只提供建议，不直接执行支付操作。
- 复用 DaxPay 的交易同步、回调及异常处理链路，补充模拟支付场景和并发/集成回归测试。

**结果与边界：** 已完成本机 ONNX 推理、工作台流程及隔离 PostgreSQL 集成测试。当前语义归并保留集召回率偏低，故默认关闭；事件分析评测尚不足以证明 RAG 提升准确率。项目为本地求职演示，不接真实支付渠道，不应写成生产支付系统。

## 本地启动

需要 JDK 25、Maven 3.9+ 和 Docker Compose。下面的容器端口仅绑定到 `127.0.0.1`，密码是本地开发默认值；如果改变监听地址或共享机器，请先替换它们。

```powershell
docker compose -f paylens-compose.yml up -d
```

首次启动空数据库时，在仓库根目录执行：

```powershell
./scripts/init-paylens-db.ps1
```

该脚本只用于初始化空数据库，会加载上游 `_config/sql/table.sql` 和 `_config/sql/data.sql`。已有数据的数据库不要重复执行。种子 SQL 包含上游本地开发账号；只在隔离的本机演示环境使用，并按 DaxPay 的安全要求修改默认口令。

上游数据提供一个示例商户，含默认应用和默认门店。本地演示可以使用它：

本地验证可用 Maven 参数 -Dpaylens.finalName=daxpay-union-validation 生成独立 JAR，避免覆盖仍被运行中 Java 进程打开的文件；启动时使用 start-paylens.ps1 -JarName daxpay-union-validation.jar -AiMode mock，强制假模型以免业务回归产生真实模型调用。

```powershell
mvn -pl daxpay-start -am -DskipTests package
./scripts/start-paylens.ps1
```

启动脚本首次运行时生成本地 RSA/AES 演示密钥，保存在仓库根目录被 Git 忽略的 `.paylens-local-secrets.json`，后续启动会复用；密钥通过进程环境变量传给 Java，并在脚本退出时清除。此文件必须和本地 PostgreSQL/Redis 数据一起保管，不要提交或在保留数据库数据时删除，否则旧加密数据可能无法解密。它们仅供隔离的 PayLens 演示配置使用。`paylens` profile 使用独立的本机回环通道地址，不加载上游 `dev` profile 里的示例密钥和个人文件路径。

模型参数填写在仓库根目录 `.env` 中；它已加入 Git 忽略规则。`.env.example` 是不含密钥的模板。启动脚本只读取列入白名单的 PayLens AI 和演示商户变量，不执行 `.env` 内容；改配置后需要重启应用。

### 异常事件工作台与本地向量模型

批量扫描读取已有异常订单、失败回调和明确的查单错误/未知状态，保存带来源 ID 的输入快照。模拟场景名、预期原因和测试标签不进入归并特征。归并先检查商户、业务、阶段、通道、异常类型和错误码边界，再使用完全链接相似度分组；事件时间跨度最多 10 分钟。人员可以带备注拆分或合并事件，旧版本和调整理由保留。

事件分组使用本机 `BAAI/bge-small-zh-v1.5` 的 ONNX CPU 模型，模型文件不会随 Git 提交，应用启动时也不会联网下载。需要准备文件时在仓库根目录运行：

```powershell
./scripts/prepare-bge-model.ps1
```

脚本固定上游模型修订、导出依赖和 SHA-256，并用 Transformers 参考实现核对 CLS 池化、L2 归一化和成对相似度排序。Java 运行时通过 Spring AI `EmbeddingModel` 调用 ONNX Runtime；找不到模型或本地推理失败时，批次会标记为 `RULE_ONLY` 并保留规则分组结果。

语义自动合并默认关闭。项目评测在开发集上得到候选阈值 0.82，但保留集召回率只有 1.1%；虽然这批合成样例没有误合并，模型仍漏掉了绝大多数同类记录，所以默认配置不启用自动语义合并。工作台仍可通过人工拆分/合并修正事件。完整数据、结果和限制见 [事件归并评测](grouping-evaluation-report.md)。不要把这组小型合成数据的结果当作生产准确率。

云端 Spring AI ChatClient 只分析单个事件的脱敏证据快照和至多三段故障知识；事件数量、交易数、时间和金额由 Java 计算。模型只能提供有引用的排查建议，不能调用支付处置接口。向量模型用于本地事件归并，不代替当前基于关键词的故障知识检索。

后端默认绑定 `127.0.0.1:9999`。用已有 DaxPay 管理端登录会话打开 `http://127.0.0.1:9999/paylens`；工作台接口沿用 DaxPay 的 `Accesstoken`、异常订单菜单权限及 VIEW/MANAGE 动作权限，并从已认证会话校验运营端身份。商户端即使拥有相同菜单权限也不能访问 PayLens；请求头中的客户端标识不作为身份判断依据。没有匿名诊断 API。仓库不含上游独立管理前端，实际使用时需要已有管理前端或同源反向代理提供登录会话。

如果示例商户或应用标识在上游种子数据中变化，创建新的本地演示商户，启用应用并设置默认门店，再把真实商户号和应用号写入 `PAYLENS_DEMO_MCH_NO`、`PAYLENS_DEMO_APP_ID`。不要把生产商户配置到 PayLens 模拟通道。

### 云端模型

默认 `PAYLENS_AI_MODE=mock`，不会调用外部模型。需要使用兼容服务时，编辑仓库根目录 `.env`：

```dotenv
PAYLENS_AI_MODE=openai
PAYLENS_AI_BASE_URL=https://api.example.com/v1
PAYLENS_AI_MODEL=你的模型标识
PAYLENS_AI_API_KEY=你的 API 密钥
PAYLENS_AI_CONNECT_TIMEOUT_SECONDS=5
PAYLENS_AI_REQUEST_TIMEOUT_SECONDS=30
```

兼容服务仅使用 Chat Completions JSON 输出模式，基础地址不要包含 `/chat/completions`。`.env` 只用于本地开发，不要分享或提交；改成 `openai` 后请确认服务商支持 Bearer 鉴权和 `response_format: json_object`。本地业务状态和人工处置不依赖模型返回；API 超时、429、非法 JSON 和越权引用会被标为分析失败，用户可以显式重试。

## 演示脚本（约 3 分钟）

1. 创建“正常成功回调”，展示本地交易、通道模拟状态和回调证据。
2. 创建“通道成功、回调丢失”，先看到本地 `processing`，再点“查询模拟通道”，展示 DaxPay 的支付同步流程将状态恢复为成功；也可以用“回放成功回调”展示回调幂等路径。
3. 创建“本地关单后收到成功”，在待处理异常里打开时间线，触发 AI 分析并检查每条事实的证据 ID 和知识引用。
4. 在人工确认前点击“重新查单”；只有最新 DaxPay 同步记录明确为 `success` 才接受确认。切换到“忽略”时必须填写备注。
5. 创建“查单超时、结果未知”，点“查询模拟通道”，确认本地保持处理中且不被推断为失败。

重复回调场景用于展示 DaxPay 自带回调处理的幂等性。所有模拟商户数据都应是非生产用途。

## 构建与验证

```powershell
mvn -pl paylens-analysis,paylens-incidents,paylens-demo -am test
mvn -pl daxpay-start -am -DskipTests "-Dpaylens.finalName=daxpay-start-validation4" package
```

完整验证矩阵与命令见 [测试方案与执行记录](test-plan.md)。截至 2026-09-26，分析模块最新单测 46/46 通过；同日此前归并领域 15/15、演示模块 11/11 通过，本次只重跑了有改动的分析模块。模型准备后已实际运行本机 ONNX 推理和开发/保留集归并评测。PostgreSQL 集成测试通过 [专用脚本](../../scripts/test-paylens-postgres-integration.ps1) 创建只绑定回环地址、没有数据卷的临时数据库，结束后清理该测试容器，不重启或复用现有演示数据库。

PostgreSQL 集成测试 10/10 通过：任务队列 5 项、事件拆分/合并 5 项，分别使用隔离数据库。单笔诊断的真实模型 A/B 结果见 [原评测记录](evaluation-report.md)。另完成事件分析的 38 次真实模型 A/B 调用：v1 提示词下仅证据组完整校验通过 10/19、证据加检索组 12/19。Codex 按评分要点给出的语义均分分别为 1.63/2 和 1.68/2，配对结果 4 胜、4 负、11 平；评分者可能从知识引用字段推测条件，且不是独立人工评分，不能据此宣称 RAG 提升正确率。全部原始响应、评分表和限制见 [事件分析评测报告](incident-evaluation-report.md)。提示词 v2 已补明 JSON 字段类型，尚未做真实 API 验证。成本未估算；本次未记录可核实的价格费率。本地 BGE 归并保留集的完全链接召回率为 1.11%，所以语义自动合并默认关闭，不能把模型已运行说成归并效果已达标。登录后的新事件工作台已通过假模型浏览器闭环：批量扫描、事件分析、导出、拆分、合并及进度刷新；本次摘要与截图见[测试执行记录](test-plan.md)。

GitHub Actions 使用 JDK 25 执行 PayLens 模块测试并打包启动应用。此基线不能在 JDK 21 下编译：固定的 DaxPay 上游版本将 `maven.compiler` 源码/目标设为 Java 25，且包含 Java 21 不支持的 API；因此本项目不伪造 JDK 21 兼容性结论。

## 当前边界

- 固定基于 DaxPay `4.0.0-beta7`（Spring Boot 4.1.1、Java 25）；未将它降级到 Java 21。需要 JDK 25。
- 模拟支付结果经 PayLens 本地通道状态表保存；支付、查单、关闭由 DaxPay 策略接口调用，状态调整由 DaxPay 的回调/同步/关闭服务完成。该通道永远不调用外部支付平台。
- 分析任务和演示事件使用 PostgreSQL；任务上限、模型超时、重启恢复和失败重试按单实例演示实现。未做多实例队列竞争、生产密钥轮换或高可用验证。
- 时间线只收集订单、回调、查单、异常单和人工处置摘要，不发送完整回调报文、认证头或通道密钥。
- 故障知识检索按显式中文关键词和状态过滤；本机向量模型只用于事件语义归并。语义归并保留硬边界和人工审计，当前保留集召回偏低，因此默认关闭自动合并。AI 建议仅作人工排查参考，不代表真实诊断准确率。
- 未提供清算、账务总账、真实支付接入、自动退款或自动修复功能。
