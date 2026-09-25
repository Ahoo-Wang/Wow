# wow-generator 设计

**状态**：2026-09 首发前的架构审查与重构（B0～B7，#3355～#3403）已全部完成。本页由当时的重构方案并入：第 1～5 节写现在的设计，附录保留方案原文（审查发现、目标架构、批次与每批的实施记录、拍板的问题、性能基线），编号照旧，附录里的「§x」指附录内的节。
**范围**：`typescript/wow-generator` 的职责、分层、数据流、公开面、测试与性能。命令与目录的速查在包的 `AGENTS.md`。

## 1. 它是做什么的，什么必须成立

用户有三类：

| 用户                         | 怎么用                                                                             | 他们需要什么成立                                                                        |
| ---------------------------- | ---------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| Wow 服务的前端开发者         | CLI：`wow-generator generate -i http://…/v3/api-docs -o src/generated`，产物提交库 | 产物能编译（strict、`NodeNext`、`bundler`）；重新生成只在文档变了的地方变；大文档也要快 |
| 调用任意 OpenAPI 3 服务的人  | 同上，文档不是 Wow 的                                                              | 通用的模型和 API 客户端；Wow 的约定不能误伤非 Wow 文档                                  |
| 构建脚本、模板项目（程序化） | `new CodeGenerator(options).generate()`                                            | 一个小而稳的 API：选项、结果、日志器、错误类别；不泄漏 ts-morph、不泄漏内部模型         |

不变量，任何改动都不能碰：

1. **产物确定**：同一份文档、同一份配置，不论在哪台机器、哪个目录、哪种 locale 生成，字节都相同。
2. **产物能编译**：写出的每个文件，名字都有声明或导入，没有重复声明；`finalize/verification.ts` 在落盘前检查。
3. **只动自己写的文件**：清单 `.wow-generator.json` 记下写过的文件和哈希，旧文件只在没被手改过时才删；中断的运行不改清单。
4. **Wow 约定只作用于 Wow 文档**：聚合、命令、事件、状态、查询字段、`tenantId`/`ownerId` 这些规则只从 Wow 的元数据推出，全部在 `wow/conventions.ts`。
5. **失败说得清**：用户能处理的失败是 `GeneratorError`，按类别映射到退出码；生成器自己的缺陷才落到 `internal`。

## 2. 架构

### 2.1 原则

1. **四段流水线，每段只做一件事**：加载（配置、文本 → 校验过的文档）→ 分析（文档 → 纯数据的 `GenerationModel`）→ 发射（模型 → 每个文件一次写好的内容）→ 收尾并落盘（格式化、类型导入、校验、清单）。
2. **只有发射、收尾、落盘碰 ts-morph**。读取和分析是纯函数，测试不需要 ts-morph；eslint 的 `no-restricted-imports` 守着。
3. **Wow 约定集中在一个模块**（`wow/conventions.ts`），分析层通过它认出 Wow 文档。
4. **警告是返回值**：每段把警告作为一行行文字返回，只有流水线交给 `Logger`，并据此计数（`GenerationResult.warnings`、`--strict`）。错误一律抛 `GeneratorError`，所以警告不需要严重度字段。
5. **依赖单向**，由 eslint 的 `import-x/no-restricted-paths` 与 `import-x/no-cycle` 守着，目录级没有环。
6. **不开放插件 API**：`GenerationModel`、`ModuleBuilder` 是内部接缝，有具体需求时再在 9.x 小版本以新增方式开放（附录 Q4）。

### 2.2 分层

| 目录        | 职责                                                                                                                                      | 碰 ts-morph                  |
| ----------- | ----------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------- |
| `index.ts`  | 只做公开再导出                                                                                                                            | 否                           |
| `api/`      | 公开类型与值：`GeneratorOptions`、`GenerationResult`、配置类型、`Logger`、`ConsoleLogger`、`SilentLogger`、`GeneratorError`、`EXIT_CODES` | 否                           |
| `cli/`      | commander 程序与用法错误的退出码（`program.ts`）、`runGenerate`（选项、退出码）、`generateAction`（Ctrl-C）                               | 否                           |
| `pipeline/` | `CodeGenerator` 串起各段、记警告；`seams.ts` 的内部接缝 `PROJECT_SEAM`、`SIGNAL_SEAM`                                                     | 经各段                       |
| `input/`    | 加载资源（文件、http）、JSON/YAML、文档校验、配置读取与校验（警告作为返回值）                                                             | 否                           |
| `openapi/`  | `OpenApiDocument`（endpoint 只算一次、合并路径级参数）、组件与引用、操作、响应、schema 判定                                               | 否                           |
| `naming/`   | 标识符与大小写、固定的 en-US 排序、`ModelInfo`、文件布局（`modelFilePath`、`boundedContextFilePath`）与 `combinePaths`                    | 否                           |
| `wow/`      | `conventions.ts`（全部 Wow 约定）、`model.ts`（`WowModel`）、`resolveWowModel`（纯函数，不改文档）                                        | 否                           |
| `analysis/` | 文档 + Wow 模型 + 配置 → `GenerationModel` 与警告：模型与限界上下文、聚合的命令与查询客户端、API 客户端；组件 key → 模型名                | 否                           |
| `types/`    | `typeResolver.ts`：schema → 类型文本与它需要的导入，纯函数                                                                                | 只借 `CodeBlockWriter.quote` |
| `emit/`     | 写入工具：`ModuleBuilder`（每个文件的语句结构，一次写入）、`ImportRegistry`、导入说明符、JSDoc 文本；不认识模型                           | 是                           |
| `emitters/` | 读 `GenerationModel`、经 `emit/` 写出：模型（`ModelEmitter`）、限界上下文、查询、命令、API 客户端、index 文件                             | 是                           |
| `finalize/` | 格式化、整理导入、类型导入、编译校验、文件头                                                                                              | 是                           |
| `output/`   | `OutputStore`：一次运行一个；读清单、所有权、防路径越界、清理陈旧文件、落盘                                                               | 是                           |

```mermaid
flowchart TD
  subgraph public[公开面]
    idx[index.ts] --> api[api/]
  end
  bin[cli/] --> pipe[pipeline/ CodeGenerator]
  idx --> pipe
  pipe --> input[input/]
  pipe --> oa[openapi/]
  pipe --> wow[wow/]
  pipe --> ana[analysis/ → GenerationModel]
  pipe --> ems[emitters/]
  pipe --> fin[finalize/]
  pipe --> out[output/ OutputStore]
  input --> oa
  wow --> oa
  ana --> wow
  ana --> nm[naming/]
  ems --> ana
  ems --> tr[types/]
  ems --> emit[emit/ ModuleBuilder]
  tr --> emit
  tr --> oa
  emit --> nm
  emit --> tsm[(ts-morph)]
  ems --> tsm
  fin --> tsm
  out --> tsm
```

发射器没有放进 `emit/`：`types/` 要用 `emit/jsdoc.ts` 的纯文本函数给内联对象的属性写注释，发射器又要用 `types/`，放在一起目录级就有 `emit ⇄ types` 的环。

### 2.3 一次生成

```mermaid
sequenceDiagram
  participant P as pipeline
  participant I as input
  participant W as wow
  participant O as output
  participant A as analysis
  participant E as emitters
  participant F as finalize
  P->>I: 读配置（先于文档：配置写错不必等远程文档）
  I-->>P: 配置 + 警告
  P->>I: 读文档，查悬空引用
  I-->>P: OpenApiDocument
  P->>W: resolveWowModel(document)
  W-->>P: WowModel + 警告
  P->>O: OutputStore.open(outputDir)
  P->>A: analyze(document, wow, config)
  A-->>P: GenerationModel + 警告
  P->>E: emitGeneration(model, modules)，每个文件一次写入
  P->>E: emitIndexFiles
  E-->>P: 警告
  P->>F: 格式化、整理导入、类型导入、校验、文件头
  P->>O: commit(signal)
  P-->>P: GenerationResult（files、configPath、warnings）
```

逐字节不变靠的是**解析顺序**。类型别名的判定取决于同一模块里谁先请求导入，所以发射顺序固定为：限界上下文 → 模型（文档顺序）→ 全部查询客户端 → 全部命令客户端 → API 客户端（按 tag 名排序）；一个 API 方法内先按「path、query、header，各自文档顺序」解析参数类型，再解析 body、返回类型，最后才把参数排成「必需在前」的签名。`GenerationModel` 里参数的顺序就是解析顺序。

### 2.4 关键抽象

- **`GenerationModel`**（`analysis/model.ts`）：`contexts`、`models`、`aggregates`、`apiClients`，只含名字、文件、schema 与种类，不含 ts-morph 节点和原始 `Operation`。body 分 `json`（带可省字段）、`formData`、`urlEncoded`、`text`、`binary`；返回分 `json`（`wildcard` 时解析成 `string` 的按文本）、`eventStream`（数组项的引用，及是否取 `ServerSentEvent` 的 `data`）、`text`、`response`。模型「写成 interface、enum 还是 type alias」由发射器按 schema 判断，它和类型解析交织，不另建镜像。
- **类型解析**（`types/typeResolver.ts`）：`(schema, scope) → { text, imports }` 的纯函数；调用方在下一次解析前把 `imports` 交给 `ImportRegistry.apply`，所以后一个引用看到的别名和逐条添加时相同。
- **`ModuleBuilder` / `ModuleSet`**（`emit/moduleBuilder.ts`）：发射器往里加 ts-morph 结构，每个文件最后只插入一次，打印器和逐条添加时相同；空行、枚举尾逗号、索引签名位置三处对齐逐条添加的打印结果（附录 B4 记录）。
- **`OutputStore`**（`output/outputStore.ts`）：`open`（读清单并兼容旧名，把上次清单里的文件和上一个 store 的文件移出 project）、`claim`（防越界、登记所有权、首次领取清空）、`forgetStale`、`commit`（落盘 → 删未改动的陈旧文件 → 写清单 → 删旧清单）。`CodeGenerator` 持有上一次运行的 store，同一个实例重跑时由它带走草稿。
- **接缝**（`pipeline/seams.ts`）：`PROJECT_SEAM` 让测试交入 ts-morph 项目；`SIGNAL_SEAM` 让 CLI 交入中断信号。两者都是包不导出的 symbol，不属于公开选项。

### 2.5 失败、警告与中断

- 用户能处理的失败是 `GeneratorError`：`input`（2）、`configuration`（3，含读不到的 tsconfig）、`specification`（4）、`output`（5）；其余是生成器的缺陷（1）。
- 命令行本身不合法（缺少必填选项或选项的值、未知选项或命令、没有给出命令）时，commander 输出它的提示，`runCLI`（`cli/program.ts`）经 `exitOverride()` 把 `CommanderError` 映射为 `input`（2），和 `--timeout abc` 这类非法选项值同一类；`--help`、`help`、`--version` 仍以 0 退出。退出码 1 只留给生成器的缺陷。
- 警告由读配置、Wow 模型、分析、index 文件四处返回，流水线按到达顺序记日志并计数：配置的警告排在最前。
- Ctrl-C：`generateAction` abort 一个信号，不再 `process.exit`。流水线在每个 await 之后检查，远程读取把它和超时合成一个；一旦开始写，就写完已开始的文件，之后不删陈旧文件、不写清单，退出码 130。再按一次 Ctrl-C 按默认行为立即结束进程。

## 3. 公开面

公开面包括程序化 API、CLI、配置与清单格式，以及**生成代码本身**。首发前按附录 §4 收窄：`test/surface/root.txt` 逐名记下根入口的导出，`scripts/verify-package.mjs` 在构建时让产物与清单一致、公开声明不引用 ts-morph 和 OpenAPI 模型、构建产物不在运行时加载任何 `@ahoo-wang` 包。生成代码的每个字节由第 4 节的 golden 守着，有意的改动单独成批并列出差异（附录 B1）。

**清单格式（`.wow-generator.json`，版本 1）**：`{ "version": 1, "files": { "<相对输出目录的 / 路径>.ts": "<SHA-256 小写十六进制>" } }`，记下上一次完整运行写出的每个文件及其哈希；`outputStore.ts` 的 `MANIFEST_VERSION` 是读写的版本。清单随产物提交，所以不同版本的生成器会读到彼此写的清单，演进规则如下：

- **版本号表示「读者能不能照字面理解」**。任何让版本 1 的读者误读的改动都必须升版本：改 `files` 的键或值的含义（路径基准、哈希算法、换行处理）、删字段或改名、加一个读者必须理解才能安全删除文件的字段。只有读者可以忽略、忽略后不会误删或漏记所有权的新字段才可以留在版本 1；读者今天就忽略未知字段。
- **读者遇到比自己新的版本就停**：以 `output`（5）失败，说「由更新的 wow-generator 写出（清单版本 N），请升级 wow-generator」，不猜、不覆盖。覆盖会丢掉新版本记下的所有权，旧生成器也无从判断哪些文件可删。
- **读者读得懂所有旧版本**：新版本的生成器继续读版本 1（和 `.fetcher-generator.json`，到 v10 为止），写回时写自己的版本；降级只能靠从版本库恢复旧清单。
- **读不懂的清单说清怎么办**：不是 JSON（多半是合并冲突）时说「解决冲突，或删掉它；删掉后本次运行无法清理上一次的陈旧文件」；形状不对（没有版本、版本不是比当前新的整数、`files` 不是对象）时说「从版本库恢复，或删掉它」。三种都在写入任何文件之前失败。

### 生成代码的名字（2026-09-25 定）

首发前第二轮审查（`typescript/docs/review-2026-09-round2-packages.md` P1-13，决定 3b、3c、3d）定下生成代码的三处形状，发布后再改都是破坏性改动：

- **命令类型名不重复 `Command`**（3b）。命令的请求体类型声明为 `<请求体>Command = CommandBody<…>`；请求体本身已以 `Command` 结尾时不声明别名，方法直接用 `CommandBody<MountedCommand>`，调用方用模型本身。不叫 `MountedCommand` 的原因：别名和它包装的模型同名，`commandClient.ts` 里的导入与声明冲突，聚合的 `index.ts` 同时再导出两者也冲突。命令别名与聚合所在包的模型、或另一个命令的请求体同名（命令 `Foo` 与 `FooCommand`）时，分析层（`assertCommandTypeNamesFree`）以 `specification`（退出码 4）报错，写明命令、两个 schema，请用户改名其一；同一个请求体的两个命令共用一个别名。
- **文件名一律 camelCase**（3c）。API 客户端文件以类名首字母小写命名（`cartApiClient.ts`），与 `commandClient.ts`、`boundedContext.ts` 一致。客户端名已按不区分大小写去重，所以不会有两个客户端落在同一个文件上。在不区分大小写的文件系统上，旧文件 `CartApiClient.ts` 与新文件是同一个文件：`OutputStore` 按项目里文件的精确路径移出旧文件（ts-morph 会记住按路径查询时的大小写），并在写盘前先删只改了大小写的陈旧文件，磁盘上留下的是新名字。
- **路径变量按路径中的顺序作为位置参数**（3d）。命令方法与 API 客户端方法的路径参数按它们在路由里出现的顺序排列（`openapi/operations.ts` 的 `inPathOrder`），不再按文档列出的顺序（可能是字母序，Wow 示例服务的文档就是）；路由新增一个变量时，已有的实参位置不会错开。query、header 参数仍按文档顺序。

## 4. 测试

测试按行为分组，不按实现它的类：

| 目录                          | 守什么                                                                                      |
| ----------------------------- | ------------------------------------------------------------------------------------------- |
| `test/goldens/`               | 产物逐字节：两份 Wow 文档、OpenAI 大文档（873 个 schema）、不同 locale 与工作目录下的确定性 |
| `test/probes/`                | 最小的文档经 CLI 生成到冷目录，再 `tsc --strict`（`NodeNext`、`bundler`）或实际运行         |
| `test/models/`                | 生成的模型类型接受什么、拒绝什么，靠对它做赋值再类型检查                                    |
| `test/analysis/`              | 表驱动断言 `GenerationModel`：方法名、参数顺序、body 与返回的种类、冲突报错、警告           |
| `test/emitters/`              | 各发射器写出的代码                                                                          |
| `test/output/`                | `OutputStore`，以及在已有输出上重新生成（清单、陈旧文件、手改文件、越界、中断）             |
| `test/cli/`、`test/pipeline/` | CLI（选项、退出码、Ctrl-C）与 `CodeGenerator` 端到端                                        |
| 其余目录                      | 同名的叶子模块（`openapi/`、`naming/`、`input/`、`types/`、`wow/`、`emit/`……）              |

golden：`expected/demo-spec/`、`expected/compensation-spec/`、`expected/openai-spec/.wow-generator.json`、`expected/warnings/`、`expected/type-resolver.json`、`expected/wow-model/`（含 `schemaDocs: 'full'` 的逐文件哈希），接受有意改动的命令见 `AGENTS.md`。仓库里另有两份逐字节产物：`typescript/integration-test/src/generated` 与 `compensation/dashboard/src/generated`，由 `typescript-contract.yml` 对同源服务端重新生成比对。

**速度**：覆盖率插桩连 TypeScript 编译器本身也插了，大约慢五倍。新建一个 ts-morph 项目或 `ts.createProgram`，都要先解析 `lib.dom.d.ts` 与 `node_modules` 里的声明，所以每次检查新建一个程序的测试几乎全部时间都花在解析上。`test/support/models.ts` 让一个测试文件的模型共用一个内存项目，探针经 `runGenerate` 的 seams 在同一个项目里生成，`typeCheck` 缓存库声明；新测试沿用这些辅助函数，并把一个文件控制在几秒，好让文件分散到各个 worker（附录 §7 的 B7 记录）。

## 5. 性能

| 文档                             | schema | 操作 | 重构前（B0 基线）         | 现在（B7 之后）          |
| -------------------------------- | ------ | ---- | ------------------------- | ------------------------ |
| `test/demo.spec.json`（Wow）     | 93     | 108  | 0.45 秒                   | 0.30 秒                  |
| `test/openai.spec.yml`（非 Wow） | 873    | 219  | 203.1 秒，峰值 RSS 792 MB | 1.09 秒，峰值 RSS 496 MB |

平方复杂度的根因（逐条增量改 ts-morph 文件）和 B4 之后的各阶段耗时见附录 §7。`pnpm --filter @ahoo-wang/wow-generator bench [spec ...]` 在构建后按阶段计时（配置、解析、Wow 模型、分析、发射、index、收尾、落盘），不进 CI；B7 之后 OpenAI 文档最大的一块是收尾（格式化、整理导入、类型导入、校验，约 0.57 秒），其次是解析文档（约 0.21 秒）。

---

## 附录：2026-09 首发前的架构审查与重构方案

**状态**：已定稿（2026-09-24，第 6 节的问题全部按建议定），按第 5 节分批实施；B0～B7 已全部合并（#3355～#3403），方案随之并入本页。
**基线**：`origin/main` `c48625e14`（R1～R4 已合并，含 #3328、#3329、#3330）。`src/` 共 7,799 行。
**范围**：`typescript/wow-generator` 的架构与代码质量。第一轮审查修的是正确性和开发体验，这一轮看职责、内聚、耦合、可扩展、可测、可读，以及要在 9.x 冻结的公开面。
**约束**：重构分批做，每批不改行为。生成物要和同源服务端逐字节一致，改动生成物的变更必须是有意的，而且单独列出。

### 1. 它是做什么的，现在长什么样

#### 1.1 第一性原理：谁用、什么必须成立

用户有三类：

| 用户                         | 怎么用                                                                             | 他们需要什么成立                                                                        |
| ---------------------------- | ---------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| Wow 服务的前端开发者         | CLI：`wow-generator generate -i http://…/v3/api-docs -o src/generated`，产物提交库 | 产物能编译（strict、`NodeNext`、`bundler`）；重新生成只在文档变了的地方变；大文档也要快 |
| 调用任意 OpenAPI 3 服务的人  | 同上，文档不是 Wow 的                                                              | 通用的模型和 API 客户端；Wow 的约定不能误伤非 Wow 文档                                  |
| 构建脚本、模板项目（程序化） | `new CodeGenerator(options).generate()`                                            | 一个小而稳的 API：选项、结果、日志器、错误类别；不泄漏 ts-morph、不泄漏内部模型         |

所以下面这些必须成立，也就是重构不能碰的不变量：

1. **产物确定**：同一份文档、同一份配置，不论在哪台机器、哪个目录、哪种 locale 生成，字节都相同。
2. **产物能编译**：写出的每个文件，名字都有声明或导入，没有重复声明（`verifyGeneratedCode` 这张安全网要留着）。
3. **只动自己写的文件**：清单 `.wow-generator.json` 记下写过的文件和哈希，旧文件只在没被手改过时才删。
4. **Wow 约定只作用于 Wow 文档**：聚合、命令、事件、状态、查询字段、`tenantId`/`ownerId` 这些规则，只从 Wow 的元数据推出来。
5. **失败说得清**：用户能处理的失败是 `GeneratorError`，按类别映射到退出码；生成器自己的缺陷才落到 `internal`。

#### 1.2 现状模块图

| 模块                | 文件（行数）                                                                                                                                                                                               | 实际承担的职责                                                                                                                                              |
| ------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 入口 / 编排         | `index.ts` (398)                                                                                                                                                                                           | 公开再导出；`CodeGenerator` 串起全流程；生成 index 桶文件；格式化、整理导入、类型导入、校验、加文件头                                                       |
| CLI                 | `cli.ts` (77)、`utils/clis.ts` (212)                                                                                                                                                                       | commander 定义；参数解析、退出码、SIGINT                                                                                                                    |
| 上下文              | `generateContext.ts` (103)、`types.ts` (149)                                                                                                                                                               | `GenerateContext` 汇集 project、文档、输出目录、聚合、配置、日志、忽略的路径参数；`types.ts` 里公开类型和内部类型混放                                       |
| Wow 聚合解析        | `aggregate/` (739)                                                                                                                                                                                         | 从 tag 和 operationId 认出聚合、命令、事件、状态、查询字段                                                                                                  |
| 模型生成            | `model/` (1,418)                                                                                                                                                                                           | 过滤 Wow 自带 schema、防重名、按 schema key 放文件、写 interface/enum/type alias；`TypeGenerator` 同时是 schema→类型的解析器                                |
| 客户端生成          | `client/` (1,832)                                                                                                                                                                                          | API 客户端（按 tag）、命令客户端、查询客户端；每个类既分析 OpenAPI 又直接写 ts-morph                                                                        |
| 工具杂物间 `utils/` | 14 个文件 (2,951)：`clis`、`components`、`configuration`、`logger`、`naming`、`operations`、`parsers`、`references`、`resources`、`responses`、`schemas`、`sourceFiles`、`typeOnlyImports`、`verification` | OpenAPI 读取、命名、配置、日志、加载、清单与输出、导入、JSDoc 渲染、类型导入、编译校验、CLI 执行，全挤在一个桶里，由 `utils/index.ts:14-27` 整体 `export *` |

#### 1.3 现状依赖与数据流

```mermaid
flowchart TD
  cli[cli.ts] --> idx[index.ts CodeGenerator]
  cli --> utils
  idx --> utils
  utils -. "utils/clis.ts:17 值导入" .-> idx
  idx --> agg[aggregate/]
  idx --> ctx[GenerateContext]
  idx --> model[model/]
  idx --> client[client/]
  ctx --> utils
  agg --> utils
  model --> utils
  utils -. "sourceFiles.ts:18 类型" .-> model
  types[types.ts] --> agg
  agg -. 类型 .-> types
  client --> model
  client --> utils
  client --> agg
  client -. "client/utils.ts:14 运行时导入" .-> wowclient[(@ahoo-wang/wow-client)]
  utils -. 运行时导入 .-> fetcher[(@ahoo-wang/fetcher)]
  model --> tsm[(ts-morph SourceFile)]
  client --> tsm
  utils --> tsm
```

一次 `generate()`（`index.ts:140-222`）的数据流：

1. `parseOpenAPI` 读取并校验文档（`:153`），`findDanglingReferences` 查悬空引用（`:155-164`）。
2. `new AggregateResolver(openAPI, logger).resolve()`（`:167-168`）：**会改写传进来的文档**，见 F7。
3. `resolveConfiguration`（`:172-176`）：配置在文档之后才读，配置写错要等文档拉取、解析完才报。
4. `beginGeneration(project, outputDir)`（`:178`）：读清单，把状态存进模块级 `WeakMap<Project, …>`（`utils/sourceFiles.ts:33-41`）。
5. `ModelGenerator.generate()`：一边分析一边往 ts-morph `SourceFile` 里逐条 `addInterface`、`addProperty`、`addJsDoc`。
6. `ClientGenerator.generate()`：查询、命令、API 三种客户端，同样边分析边写。
7. 生成 index 桶文件、`formatText` + `organizeImports`、`applyTypeOnlyImports`、`verifyGeneratedCode`、插入文件头（`:347-368`）。
8. `saveGeneration`：写文件、删未改动的旧文件、写清单。

分析（OpenAPI → Wow 领域 → 要生成什么）和发射（ts-morph）之间**没有中间模型**。每个生成器都拿着 `GenerateContext`，直接读原始 `Operation`/`Schema`，自己解析引用，再直接改 `SourceFile`。

#### 1.4 测试现状

| 层                         | 在哪                                                                                | 守住什么                                                                                                                            |
| -------------------------- | ----------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| 单元测试                   | `test/utils/*`、`test/model/*`、`test/client/*`、`test/aggregate/*`                 | 贴着内部函数和类写（直接驱动 `CommandClientGenerator`、调私有的 `resolveType`），重构一动就要跟着改                                 |
| 类型约束测试               | `schemaConstraints`（1,264 行）、`compositionConstraints`、`enumConstConstraints`   | 用 `TypeGenerator` 的构造函数把 schema 写进内存项目，再看诊断；守的是类型语义，但绑在 `TypeGenerator` 的签名上                      |
| 行为探针                   | `test/probes.test.ts`（830 行）、`regeneration`、`cli`、`index`                     | 从 CLI 生成到冷目录，再 `tsc --strict`（`NodeNext`、`bundler`）或实际运行；和实现无关，是重构最可靠的网                             |
| 逐字节 golden              | `expected/demo-spec`、`expected/compensation-spec`（`test/e2e.test.ts:196`）        | 两份都是 Wow 文档（93、120 个 schema），没有非 Wow 的大文档                                                                         |
| 仓库里的另外两份逐字节产物 | `typescript/integration-test/src/generated`、`compensation/dashboard/src/generated` | `typescript-contract.yml` 对同源 example-server 重新生成并比对（`:128-139`）；8.10.8、8.11.5 两个镜像的产物做类型检查（`:204-218`） |
| 公开面                     | `test/packageEntries.test.ts` 只断言几个名字能加载                                  | **没有逐名清单**。view-engine 已按 D29 做了（`wow-view-engine/test/publicSurface.test.ts`），生成器还没有                           |

`test/openai.spec.yml`（69,574 行，873 个 schema）**没有任何测试引用**，正好拿来当大文档 golden 和性能基准。

### 2. 发现

严重度：**P0** 首发前必须处理（发出去就冻结或伤用户）；**P1** 结构性问题，用户已要求首发前完成，但发布后改也不破坏任何人；**P2** 顺手清掉。

#### 2.1 总表

| #   | 严重度 | 类别              | 发现                                                                                            |
| --- | ------ | ----------------- | ----------------------------------------------------------------------------------------------- |
| F1  | P0     | 性能 / 架构       | 模型逐属性增量改 ts-morph 文件，大文档是平方复杂度：873 个 schema 要 214 秒                     |
| F2  | P0     | 生成物正确性      | 流式命令客户端用了 `JsonEventStreamResultExtractor`，错误事件不会变成 `WowError`                |
| F3  | P0     | 命名 / 生成物     | 类型名把缩写压小写：`MCPListTools` → `McplistTools`（R2-31）                                    |
| F4  | P0     | 公开面            | 公开声明泄漏 ts-morph 与内部模型；`Logger` 接口形状不适合冻结；没有公开面清单                   |
| F5  | P1     | 架构              | 没有中间模型：分析和发射搅在一起，`GenerateContext` 是万能上下文                                |
| F6  | P1     | 内聚 / 耦合       | Wow 约定散在 6 个文件里                                                                         |
| F7  | P1     | 架构 / 正确性     | `AggregateResolver` 改写输入文档，模型的注释取决于执行顺序                                      |
| F8  | P1     | 内聚              | `utils/` 是杂物间；`sourceFiles.ts` 一个文件四种职责；模块级可变状态                            |
| F9  | P1     | 耦合              | 运行时循环依赖 `index ⇄ utils/clis`；类型层循环 `utils ⇄ model`、`types ⇄ aggregate`            |
| F10 | P1     | 抽象泄漏          | `ApiClientGenerator` 伪造 `ModelInfo` 和空 schema 来借用 `TypeGenerator`                        |
| F11 | P1     | 错误处理          | 循环引用、外部引用、清单损坏、路径越界抛的是普通 `Error`/`TypeError`，退出码成了「内部缺陷」    |
| F12 | P1     | 确定性            | 排序用 `localeCompare()`，结果取决于运行机器的 locale                                           |
| F13 | P1     | 性能 / 确定性     | ts-morph `Project` 会把用户 tsconfig 里的全部源文件加载进来                                     |
| F14 | P1     | 可测性            | 测试按审查编号组织、贴着内部实现；缺大文档 golden、公开面清单和警告 golden                      |
| F15 | P2     | 重复              | 同一份 endpoint 列表算三遍；`TypeGenerator` 每解析一次引用就扫一遍全部 schema                   |
| F16 | P2     | 日志              | 日志噪声大、`any` 参数、只打日志的空循环                                                        |
| F17 | P2     | 死代码 / 文档漂移 | 未用的导出、未用的参数、AGENTS.md 结构表过时                                                    |
| F18 | P2     | 耦合              | 生成器运行时导入 `wow-client`、`fetcher`，只为几个字符串常量                                    |
| F19 | P2     | 命名              | 名字和行为对不上：`createClientFilePath` 返回 `SourceFile`、`stateAggregatedTypeNames` 有副作用 |
| F20 | P2     | 健壮性            | 配置晚于文档读取；Ctrl-C 可能只写了一半                                                         |

#### 2.2 详述

**F1 — 大文档平方复杂度（P0，性能/架构）**

- 证据：`ModelGenerator.generateKeyedSchema` 为每个 schema `new TypeGenerator(...)`（`src/model/modelGenerator.ts:223-231`）。`processInterface` 先 `addInterface`，再对每个属性调 `addPropertyToInterface`（`src/model/typeGenerator.ts:853-866`），后者 `addProperty` 之后又 `addSchemaJSDoc`（`:832-851`）。模型最后还要 `addMainSchemaJSDoc`（`:263-273`）。ts-morph 的每次改动都会替换整个文件的文本、重新解析，再重新包装节点。没有包路径的 schema 全放进根目录一个 `types.ts`，OpenAI 文档里这个文件有 21,881 行，于是每加一个属性就要重新解析一遍越来越大的文件。
- 实测：见 §7。873 个 schema 墙钟 213.7 秒；CPU 剖析显示 `ModelGenerator.generate` 占 83%（193 秒），其中 `addPropertyToInterface` 152 秒。ts-morph `doManipulation` 133 秒，`addJsDoc` 92 秒。自耗时排前面的是 TypeScript 扫描器 `scan`（37 秒）、GC（33 秒）、`doJSDocScan`（17 秒），都是在反复解析。格式化、整理导入、类型导入、编译校验合计只有 0.7 秒。所以「重复解析」的说法对，但病根不在收尾阶段的 ts-morph，而在发射方式：逐条增量改。
- 为什么要紧：生成器的用户正是有大文档的团队，3～4 分钟一次会让人不愿意重新生成，产物就和服务端漂移了。而且这是发射层的形状问题，不重写发射方式修不好（见 §3、B4）。

**F2 — 流式命令客户端吞掉错误事件（P0，生成物正确性）**

- 证据：`CommandClientGenerator.processStreamCommandClient` 把 `STREAM_RESULT_EXTRACTOR_METADATA` 放进 `@api(...)`（`src/client/commandClientGenerator.ts:349-355`），这个常量写死了 `JsonEventStreamResultExtractor`（`src/client/decorators.ts:71-74`）。wow-client 自己的 `CommandClient` 用的是 `CommandResultEventStreamResultExtractor`（`wow-client/src/command/commandClient.ts:107`），它会在服务端发来错误事件时报 `WowError`（`wow-client/src/eventStreams.ts:76-90`）。
- 为什么要紧：同一个 `sendAndWaitStream` 场景，手写客户端报错，生成的客户端却把错误事件当成一条数据往下传。类型都是 `CommandResultEventStream`，调用方看不出区别。这属于有意改变生成物，放在 B1 单独做。

**F3 — 缩写被压成小写（P0，命名/生成物，R2-31）**

- 证据：`pascalCase` 对每个词执行 `rest.toLowerCase()`（`src/utils/naming.ts:203-205`），`splitCamelCase` 只在「小写后跟大写」处切分（`:151-180`），所以 `MCPListTools` 被当成一个词，变成 `McplistTools`。类型名都经过这里（`toTypeIdentifier`，`:119-121`；`resolveModelInfo`，`src/model/modelInfo.ts:96`）。
- 实测：OpenAI 文档 873 个 schema 里有 52 个改了名（`FineTuneDPOMethod` → `FineTuneDpomethod`、`MCPTool` → `Mcptool`）。demo、compensation 两份 Wow 文档里是 0 个。
- 为什么要紧：生成出来的类型名就是用户代码要引用的 API。首发后再改，所有非 Wow 用户都要跟着改代码；首发前改，Wow 用户零影响。见 Q1。

**F4 — 公开面没准备好冻结（P0，公开面）**

- 证据：
  - `CodeGenerator` 的构造函数带一个 `/** @internal */ project?: Project`（`src/index.ts:99-103`），但构建没开 `stripInternal`，于是 `dist/index.d.ts` 第 1 行就是 `import { Project } from 'ts-morph'`。
  - 公开类型和内部类型都放在 `types.ts`：`GenerateContextInit` 在 `:107-125`，它引用 `Project` 和 `BoundedContextAggregates`。结果 `dist/types.d.ts` 把 ts-morph、`@ahoo-wang/fetcher-openapi` 和整套聚合内部类型都带进了公开声明。
  - `Logger` 有 6 个方法：`progress`、`progressWithCount` 带缩进层级参数，`warn` 却是可选的，靠 `warn()` 辅助函数回退到 `info`（`src/types.ts:70-102`、`src/utils/logger.ts:146-156`）。参数是 `any[]`。这层兼容是给 fetcher-generator 时代的日志器留的，而 `@ahoo-wang/wow-generator` 在 npm 上还是新包。
  - 没有逐名的公开面清单（D29 那样的）。`packageEntries.test.ts` 只检查几个名字能加载。
- 为什么要紧：9.2.0 是这个包名的第一次发布，现在收窄不算破坏。发布以后，每个多出来的名字和每个参数都是兼容负担。见 §4、Q2、Q3。

**F5 — 没有中间模型，`GenerateContext` 什么都管（P1，架构）**

- 证据：
  - `GenerateContext`（`src/generateContext.ts:25-95`）同时持有 ts-morph `Project`、原始文档、输出目录、聚合、日志、配置、聚合 tag、文档注释档位，外加两条策略方法。生成器全都 `implements Generator { generate(): void }`（`:97-103`），没有返回值，只能靠检查 ts-morph 项目来测。
  - `ApiClientGenerator` 一个类（645 行）里既有 tag 过滤（`resolveApiTags`，`src/client/apiClientGenerator.ts:620-644`）、操作分组（`:558-605`）、方法命名与冲突检测（`:235-255`）、请求体和返回类型推断（`:273-323`、`:437-488`），也有 ts-morph 发射（`:183-211`、`:498-547`）。
  - `AggregateDefinition` 名义上是领域模型，却带着原始 `operation: Operation` 和 `KeySchema`（`src/aggregate/aggregate.ts:23-47`），客户端生成器还得回头读 OpenAPI。
- 为什么要紧：「这个文档会生成什么」没法不借助 ts-morph 单独测，也没法单独复用。加一种产物（比如 R2-21 的打包，或 React hooks）要改好几个地方。分析和发射混在一起，也正是 F1 修不干净的原因。

**F6 — Wow 约定散落（P1，内聚/耦合）**

- 证据：`operationId` 后缀 `.snapshot_state.single`、`.event.list_query`、`.snapshot.count`、`wow.command.send`，以及 `CommandOk` 引用、快照路由正则，在 `src/aggregate/aggregateResolver.ts:48-60, 189, 273, 319, 401`；`wow.` 前缀与 8 个后缀规则、聚合派生类型的后缀表在 `src/model/modelGenerator.ts:118-165`；schema 到 wow-client 类型的映射和 legacy 集合在 `src/model/wowTypeMapping.ts:26-70`；要跳过的 tag `wow`、`Actuator` 在 `src/client/apiClientGenerator.ts:607-613`；`tenantId`、`ownerId` 在 `src/generateContext.ts:41`；资源归属推断在 `src/client/utils.ts:53-72`。
- 为什么要紧：Wow 服务端的 OpenAPI 约定是这个包存在的理由，也最常随服务端版本变化（8.10、8.11 的兼容分支就是证据）。散在各处，改一个约定得搜遍全包，契约也没法对着一个模块测。

**F7 — 解析器改写输入文档（P1，架构/正确性）**

- 证据：`commands()` 把操作的 `summary`、`description` 写进 components 里的命令 schema（`src/aggregate/aggregateResolver.ts:248-251`）；`events()` 同样改事件 schema 的 `title`（`:375-376`）。模型生成器后来读的正是这些被改过的 schema，所以模型的 JSDoc 取决于解析器是否先跑过。类注释（`:70-74`）承认了这一点，把「给我一份你自己的文档」的责任推给了调用方。
- 为什么要紧：阶段之间通过改共享对象来通信，是隐式耦合：调换顺序或者复用解析器，产物就变了。目标模型里应当显式记下「文档覆写」。

**F8 — `utils/` 杂物间与模块级状态（P1，内聚）**

- 证据：
  - `utils/index.ts:14-27` 把 14 个文件整体再导出。里面有 OpenAPI 读取、命名、配置、日志、网络加载、CLI 执行，还有输出清单。
  - `utils/sourceFiles.ts`（590 行）一个文件做了四件事：清单与所有权（`:26-189`）、路径越界防护（`:214-232`）、导入辅助（`:270-390`）、JSDoc 渲染（`:396-590`）。
  - 运行状态放在模块级 `WeakMap<Project, …>` 里（`:33-41`）。`getOrCreateSourceFile` 看名字是查询，实际会清空文件并登记为本次所写（`:241-262`）。
- 为什么要紧：看不出哪些是 OpenAPI 层、哪些是输出层；全局状态让「同一个 Project 生成两次」的语义只能靠 `beginGeneration` 的调用顺序来保证。

**F9 — 循环依赖（P1，耦合）**

- 证据：`index.ts` 导入 `./utils`（`:22-36`），`utils/index.ts` 再导出 `clis`，而 `utils/clis.ts:17` 又值导入 `../index` 的 `CodeGenerator`，这是运行时循环。类型层还有两个循环：`utils/sourceFiles.ts:18` → `model`、`model/*` → `utils`；`types.ts:16` → `aggregate`，`aggregate/aggregateResolver.ts:31` → `types`。
- 为什么要紧：现在靠打包后的执行顺序侥幸能跑。分层也立不起来，最底层的 `utils` 同时依赖最顶层的 CLI。

**F10 — 借用 `TypeGenerator` 的伪造参数（P1，抽象泄漏）**

- 证据：`new TypeGenerator({ name: className, path: '\0client' }, apiClientFile, { key: '', schema: {} }, …)`（`src/client/apiClientGenerator.ts:199-205`）。API 客户端只想要「schema → 类型表达式并登记导入」，但这个能力只长在「生成一个模型声明」的类里。`TypeGenerator` 一个类 683 行（`src/model/typeGenerator.ts:252-934`），把类型解析、导入别名、声明发射都揉在一起。
- 为什么要紧：缺一个抽象，就会有 `'\0client'` 这种魔法值。类型解析本来可以是纯函数，可以穷举测试，现在测它必须带一个 ts-morph 文件。

**F11 — 用户能处理的失败被报成内部缺陷（P1，错误处理）**

- 证据：组件循环引用抛 `TypeError`（`src/utils/components.ts:73`、`src/aggregate/aggregateResolver.ts:205`），外部 `$ref` 也是 `TypeError`（`src/model/modelInfo.ts:106`）。清单损坏（`src/utils/sourceFiles.ts:64, 75`）和路径越界（`:228`）抛普通 `Error`。CLI 对这些一律报 `Code generation failed … report it at …/issues`，退出码 1（`src/utils/clis.ts:137-146`）。
- 为什么要紧：文档里的循环引用和外部引用是输入问题，清单损坏和输出目录问题是环境问题，都不是生成器的缺陷。报错却叫用户去提 issue。

**F12 — 排序依赖 locale（P1，确定性）**

- 证据：endpoint 按 `operationId.localeCompare(...)` 排序（`src/utils/operations.ts:48-58`，在 `:103` 使用），index 桶文件里的导出名也用 `localeCompare`（`src/index.ts:312`）。不传 locale 时，比较结果跟着进程的默认 locale 走，比如 ICU 取 `LANG`。
- 为什么要紧：违背不变量 1。CI 和开发机 locale 不同时，桶文件的导出顺序和方法顺序可能不同，产物就会无意义地抖动。修法是固定一个排序器（`Intl.Collator('en-US')`，和现在 CI 的结果一致），这样在 CI 上字节不变。

**F13 — 把用户整个项目装进 ts-morph（P1，性能/确定性）**

- 证据：`new Project({ tsConfigFilePath })`（`src/index.ts:105-106`）没有加 `skipAddingFilesFromTsConfig`，tsconfig `include` 到的全部源文件都会被读取、解析，还会进入 `organizeImports`、类型导入、`getPreEmitDiagnostics` 用的程序。
- 为什么要紧：大应用会平白多花几秒和几百 MB 内存。用户项目里的全局声明（`declare global`）还可能影响类型导入的判定。生成器只需要 tsconfig 的**编译选项**。

**F14 — 测试的形状妨碍重构（P1，可测性）**

- 证据：
  - 测试按审查编号组织：`describe('R2-08 …')`、`describe('R2-23 …')`（`test/probes.test.ts:65-821`），还有三个文件都叫 `describe('review regressions')`（`test/regeneration.test.ts:44`、`test/openapiContracts.test.ts:36`、`test/packageEntries.test.ts:17`）。
  - 单元测试直接构造内部类、拼一个 `GenerateContext` 去驱动（`test/client/commandClientGenerator.test.ts:36`），或者用 `(generator as any).resolveType(...)` 调私有方法（`test/model/typeGenerator.test.ts:45`）。生成器里不少方法是 public 的，只是为了让测试能调。
  - golden 只有两份 Wow 文档；警告文案没有 golden；公开面没有清单。
- 为什么要紧：重构时要区分「行为变了」和「内部挪了」。现在内部一挪，一大片单元测试就红，真正守行为的探针和 golden 反倒淹在里面。

**F15 — 重复计算（P2，重复/性能）**

- 证据：`extractOperationEndpoints` 在解析器构造函数里调一次（`src/aggregate/aggregateResolver.ts:94`），`build()` 里再调一次（`:110`），`ApiClientGenerator.groupOperations` 里第三次（`src/client/apiClientGenerator.ts:562`）。`resolveApiTags` 又用 `extractOperations` 自己遍历一遍（`:622-633`），没有合并路径级参数。`TypeGenerator.resolveReference` 每解析一个跨文件引用，都要对全部 components 重算 `resolveModelInfo`（`src/model/typeGenerator.ts:328-340`），复杂度 O(引用数 × schema 数)。
- 为什么要紧：浪费不大，但同一件事有几条路径，一致性全靠巧合。

**F16 — 日志（P2）**

- 证据：`CommandClientGenerator` 一个类里有 18 处 `logger.info`/`progress`，都是把代码复述一遍（如 `src/client/commandClientGenerator.ts:105-179`）。`ClientGenerator.generate` 有个循环只打进度日志，什么也不做（`src/client/clientGenerator.ts:46-55`）。`Logger` 和 `ConsoleLogger` 的参数是 `any[]`（`src/utils/logger.ts:64-101`）。全包共有 32 处 `any`。
- 为什么要紧：噪声会淹掉警告；#3328 把细节降到 `verbose` 才看到，治的是症状。

**F17 — 死代码与文档漂移（P2）**

- 证据：`EventStreamSchema`、`DomainEventSchema`（`src/aggregate/types.ts:19-46`）、`resolveEnumMemberName`（`src/utils/naming.ts:265`）、`isUnion`（`src/utils/schemas.ts:111`）、`COMPONENTS_HEADERS_REF`（`src/utils/components.ts:27`）在 src 里没有引用。`isIgnoreCommandClientPathParameters` 的 `tagName` 参数没用到（`src/generateContext.ts:89-94`）。`addImport` 里有拼写错误 `exited`（`src/utils/sourceFiles.ts:285`）。AGENTS.md 的结构表里还有不存在的 `src/stories/`，缺 `errors.ts`、`configuration.ts`、`typeOnlyImports.ts`、`verification.ts`。`vitest.config.ts` 还在排除 `**/**.stories.tsx`。

**F18 — 为几个常量拖进运行时依赖（P2，耦合）**

- 证据：`client/utils.ts:14` 在运行时导入 `ResourceAttributionPathSpec`，只是为了拿两个路径前缀字符串；`combineURLs`、`ContentTypeValues` 来自 `@ahoo-wang/fetcher`（`src/utils/sourceFiles.ts:14`、`src/utils/responses.ts:14`、`src/aggregate/aggregateResolver.ts:29`）。生成器进程因此要加载 wow-client、fetcher 以及它们的依赖链。
- 为什么要紧：生成器是 Node CLI，生成的代码才需要这些库。和生成代码的契约应当只落在「写出去的导入说明符和名字」上，由 golden 和类型检查守住。

**F19 — 名字和行为对不上（P2，命名）**

- 证据：`createClientFilePath` 返回的是 `SourceFile`（`src/client/utils.ts:96-104`）；`stateAggregatedTypeNames()` 名字像查询，实际会生成所有 `boundedContext.ts`（`src/model/modelGenerator.ts:167-176`）；`isAliasAggregate` 返回元组或 `null`（`src/aggregate/utils.ts:27`）；`process*`、`resolve*` 这两种前缀混用，看名字分不出谁有副作用。
- 当时不改的一处：生成出来的 `MockVariableCommandCommand`（schema 名本身以 Command 结尾，再加后缀）读着别扭，但这是产物的公开名字，改了会破坏已有调用，重构期间不动。2026-09-25 首发前第二轮审查（P1-13，决定 3b）改了：以 `Command` 结尾的请求体不再声明别名，见正文 §3「生成代码的名字」。

**F20 — 失败顺序与中断（P2，健壮性）**

- 证据：配置在文档拉取、聚合解析之后才读（`src/index.ts:153-176`）；tsconfig 在构造函数里同步读，读不到抛普通 `Error`（`:105-106`）；SIGINT 直接 `process.exit(130)`（`src/utils/clis.ts:207-210`），保存到一半时会留下半套文件和旧清单。

### 3. 目标架构

#### 3.1 原则

1. **四段流水线，每段只做一件事**：加载（文本 → 校验过的文档）→ 分析（文档 → 纯数据的 `GenerationModel`）→ 发射（模型 → 每个文件一次写好的内容）→ 收尾并落盘（格式化、类型导入、校验、清单）。
2. **只有发射、收尾、落盘三段碰 ts-morph**。分析层是纯函数，测试不需要 ts-morph。
3. **Wow 约定集中在一个模块**，分析层通过它认出 Wow 文档。
4. **诊断是返回值**：分析和发射返回警告列表，只有流水线把它交给 `Logger`。`--strict`、计数和测试都读这张列表。
5. **依赖单向**：`cli → pipeline → {input, openapi, wow, analysis, emit, finalize, output}`，下层不认识上层；`naming`、`openapi` 是叶子。
6. **不加插件 API**。阶段边界先作为内部接缝，等真有需求再在 9.x 小版本里以新增方式开放，见 Q4。

#### 3.2 模块边界

| 目录（目标） | 职责                                                                                                                                                                                    | 来自                                                                                                                    | 碰 ts-morph |
| ------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- | ----------- |
| `index.ts`   | 只做公开再导出                                                                                                                                                                          | `index.ts` 的导出部分                                                                                                   | 否          |
| `api/`       | 公开类型：`GeneratorOptions`、`GenerationResult`、配置类型、`Logger`、`ConsoleLogger`、`SilentLogger`、`GeneratorError`、`EXIT_CODES`                                                   | `types.ts` 的公开部分、`errors.ts`、`utils/logger.ts`                                                                   | 否          |
| `cli/`       | commander 程序、`runGenerate`、退出码、中断                                                                                                                                             | `cli.ts`、`utils/clis.ts`                                                                                               | 否          |
| `pipeline/`  | `CodeGenerator`：串起各段，处理诊断和计时                                                                                                                                               | `index.ts:140-222`                                                                                                      | 经各段      |
| `input/`     | 加载资源（文件、http）、JSON/YAML、文档校验、配置读取与校验                                                                                                                             | `utils/resources.ts`、`parsers.ts`、`configuration.ts`                                                                  | 否          |
| `openapi/`   | `OpenApiDocument`：endpoint 只算一次、合并路径级参数、按类型解析组件引用（循环、外部引用报 `specification`）、悬空引用、媒体类型、schema 判定                                           | `utils/components.ts`、`references.ts`、`operations.ts`、`responses.ts`、`schemas.ts`                                   | 否          |
| `wow/`       | `WowConventions`：operationId 后缀、schema key 规则、到 wow-client 的类型映射与 legacy 集合、忽略的 tag、归属路径参数、资源归属推断；`resolveWowModel(doc)`：纯函数，产出聚合，不改文档 | `aggregate/*`、`model/wowTypeMapping.ts`、`modelGenerator.ts:118-165`、`generateContext.ts:41`、`client/utils.ts:53-72` | 否          |
| `naming/`    | 标识符、大小写、属性名与字符串字面量、唯一化                                                                                                                                            | `utils/naming.ts`、`client/utils.ts` 的命名部分                                                                         | 否          |
| `analysis/`  | 文档 + 配置 + Wow 模型 → `GenerationModel`：模型声明及其文件、重名检测、API 客户端（tag、方法名、参数、请求体、返回）、命令与查询客户端、限界上下文                                     | 各 `*Generator` 里的分析部分                                                                                            | 否          |
| `types/`     | `TypeResolver`：schema → 类型表达式，外加它需要的导入请求（纯函数）                                                                                                                     | `TypeGenerator.resolveType` 及相关私有方法                                                                              | 否          |
| `emit/`      | `ModuleBuilder`（每个文件的导入登记表和语句结构）；各发射器：模型、API 客户端、命令客户端、查询客户端、限界上下文、桶文件、JSDoc                                                        | 各 `*Generator` 的写入部分、`sourceFiles.ts` 的导入与 JSDoc、`index.ts:231-334`                                         | 是          |
| `finalize/`  | 格式化、整理导入、类型导入、编译校验、文件头                                                                                                                                            | `index.ts:347-368`、`utils/typeOnlyImports.ts`、`verification.ts`                                                       | 是          |
| `output/`    | `OutputStore`：一次运行一个实例；读写清单、所有权、清理旧文件、防路径越界、落盘                                                                                                         | `utils/sourceFiles.ts:26-262` 的清单与路径部分                                                                          | 是          |

#### 3.3 关键抽象

```ts
// analysis/model.ts —— 纯数据，不引用 ts-morph，也不引用原始 Operation
interface GenerationModel {
  readonly contexts: readonly BoundedContextModel[]; // alias、常量名、文件
  readonly models: readonly ModelDeclaration[]; // key、名字、文件、schema、文档覆写
  readonly aggregates: readonly AggregateModel[]; // 命令、事件、状态、字段、resourceName、归属
  readonly apiClients: readonly ApiClientModel[]; // 类名、文件、methods: ApiMethodModel[]
}
interface ApiMethodModel {
  readonly name: string;
  readonly httpMethod: HTTPMethod;
  readonly path: string;
  readonly parameters: readonly ParameterModel[]; // 位置、名字、schema、是否必需、说明
  readonly body?: BodyModel; // json(schema, optionalFields) | formData | urlEncoded | text | binary
  readonly returns: ReturnModel; // json(schema) | text | eventStream(schema?) | response
  readonly docs: readonly string[];
}
type Diagnostic = { readonly severity: 'warning'; readonly message: string };

// types/typeResolver.ts —— 纯函数
interface TypeResolver {
  resolve(schema: Schema | Reference, from: ModuleLocation): ResolvedType;
}
interface ResolvedType {
  readonly text: string; // 例如 'PartialBy<Item, 'id'>'
  readonly imports: readonly ImportRequest[]; // { module, name, typeOnly? }
}

// emit/moduleBuilder.ts —— 每个文件一个，最后一次写入
interface ModuleBuilder {
  readonly imports: ImportRegistry; // 登记、去重、同名时起别名，替代 typeGenerator.ts:313-348
  add(statement: StatementStructures): void;
  build(project: Project): SourceFile; // 整个文件只插入一次
}

// output/outputStore.ts —— 代替 WeakMap 全局状态
class OutputStore {
  static open(fs: FileSystemHost, outputDir: string): OutputStore; // 读清单，旧名兼容
  claim(relativePath: string): string; // 防越界，登记所有权
  staleFiles(): readonly string[];
  commit(files: readonly SourceFile[]): Promise<void>; // 写文件，删未改动的旧文件，写清单
}
```

F1 的修法就在 `ModuleBuilder`：发射器生成 ts-morph 的**结构**（`InterfaceDeclarationStructure` 里带上 `properties` 和 `docs`），每个文件最后只插入一次，打印器和现在是同一个。所以格式化之后的文本应当逐字节相同，由 golden 验证。别名判定改成查内存里的 `ImportRegistry`，不再问 `SourceFile`。

#### 3.4 目标依赖图

```mermaid
flowchart TD
  subgraph public[公开面]
    idx[index.ts] --> api[api/]
  end
  bin[cli/] --> pipe[pipeline/ CodeGenerator]
  idx --> pipe
  pipe --> input[input/]
  pipe --> oa[openapi/ OpenApiDocument]
  pipe --> wow[wow/ WowConventions + resolveWowModel]
  pipe --> ana[analysis/ → GenerationModel]
  pipe --> emit[emit/ ModuleBuilder + 发射器]
  pipe --> fin[finalize/]
  pipe --> out[output/ OutputStore]
  input --> oa
  wow --> oa
  ana --> oa
  ana --> wow
  ana --> nm[naming/]
  emit --> ana
  emit --> tr[types/ TypeResolver]
  tr --> oa
  tr --> nm
  emit --> tsm[(ts-morph)]
  fin --> tsm
  out --> tsm
  api -.-> none[不依赖 ts-morph 与内部模型]
```

```mermaid
sequenceDiagram
  participant P as pipeline
  participant I as input
  participant W as wow
  participant A as analysis
  participant E as emit
  participant F as finalize
  participant O as output
  P->>I: 读取配置和文档（先配置，失败早）
  I-->>P: OpenApiDocument
  P->>W: resolveWowModel(doc)
  W-->>P: WowModel + 诊断
  P->>A: analyze(doc, wow, config)
  A-->>P: GenerationModel + 诊断
  P->>O: OutputStore.open(outputDir)
  P->>E: emit(model, store)
  E-->>P: SourceFile[]（每个文件插入一次）
  P->>F: 格式化、整理导入、类型导入、校验、文件头
  P->>O: commit(files)
  P-->>P: GenerationResult（files、configPath、warnings）
```

#### 3.5 移动与删除清单

- **删除**：`aggregate/types.ts` 的 `EventStreamSchema`、`DomainEventSchema`；`resolveEnumMemberName`、`isUnion`、`COMPONENTS_HEADERS_REF`；`ClientGenerator` 里只打日志的循环；`Generator` 接口；`GenerateContext` 和 `GenerateContextInit`；`utils/index.ts` 桶文件；`warn()` 回退辅助函数（Q2 通过后）；`'\0client'` 伪造参数；模块级 `generatedFiles` WeakMap。
- **拆分**：`TypeGenerator` 拆成 `types/TypeResolver`（纯）和 `emit/models`（声明）；`ApiClientGenerator` 拆成 `analysis/apiClients` 和 `emit/apiClient`；命令、查询客户端同理；`sourceFiles.ts` 拆成 `output/`、`emit/imports`、`emit/jsdoc`。
- **合并**：Wow 约定合并到 `wow/conventions.ts`。
- **改名**：`createClientFilePath` → `clientModulePath`（只返回路径）；`stateAggregatedTypeNames` 拆成「计算派生类型名」和「生成限界上下文」两步。

### 4. 公开面影响

公开面包括程序化 API、CLI、配置与清单格式，以及**生成代码本身**。

| 面                                 | 现在                                                                         | 建议                                                                                                                                                                                        | 破坏性                            | 何时       |
| ---------------------------------- | ---------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------- | ---------- |
| 公开面清单                         | 没有                                                                         | 按 D29 加 `test/surface/root.txt` 和 `test/publicSurface.test.ts`，逐名记下，并注明是类型还是值                                                                                             | 否                                | B0         |
| `CodeGenerator` 构造函数           | `(options, /** @internal */ project?: Project)`，d.ts 里带着 ts-morph        | 只留 `(options)`。测试接缝改成不导出的内部工厂                                                                                                                                              | 形式上是，实际没人该用            | 现在（B2） |
| `types.d.ts` 的依赖                | 引用 ts-morph、fetcher-openapi、聚合内部类型                                 | 公开类型单独放 `api/`，声明文件不引用 ts-morph 和内部模型                                                                                                                                   | 否                                | 现在（B2） |
| `Logger`                           | 6 个方法，`warn` 可选，参数 `any[]`                                          | 4 个必需方法：`debug`、`info`、`warn`、`error`，`(message: string, ...params: unknown[])`。`ConsoleLogger` 级别映射：`verbose` 输出 debug；`normal` 输出 info；`quiet` 只输出 warn 和 error | 是：自定义日志器要改              | 现在（Q2） |
| `GeneratorErrorKind`、`EXIT_CODES` | `input`、`configuration`、`specification`                                    | 新增 `output`（退出码 5）：清单损坏、路径越界、写入失败。循环引用、外部引用归入 `specification`                                                                                             | 是：对联合类型做穷举的代码        | 现在（Q3） |
| `GenerationResult`                 | `files`、`configPath`、`warnings`                                            | 不变。以后可以加字段（如 `diagnostics`），属于新增                                                                                                                                          | 否                                | —          |
| `GeneratorOptions`                 | 8 个字段                                                                     | 不变                                                                                                                                                                                        | 否                                | —          |
| CLI                                | 命令、选项、退出码 0～4、130                                                 | 只随 Q3 多一个退出码 5                                                                                                                                                                      | 否（新增）                        | 现在       |
| 配置、清单格式                     | `wow-generator.config.json`、`.wow-generator.json` v1                        | 不变                                                                                                                                                                                        | 否                                | —          |
| 生成代码：流式命令客户端           | `JsonEventStreamResultExtractor`                                             | 改用 wow-client 的端点预设 `COMMAND_STREAM_ENDPOINT`（wow-client A4：`Accept` 头加 `CommandResultEventStreamResultExtractor`），从 `@ahoo-wang/wow-client` 导入。类型不变                   | 行为修正：错误事件变成 `WowError` | 现在（B1） |
| 生成代码：类型名大小写             | `MCPListTools` → `McplistTools`                                              | 本身已是合法标识符的名字段原样保留（见 Q1）。Wow 文档零变化；非 Wow 文档有改名（OpenAI 文档 49/873，B1 实测）                                                                               | 是：非 Wow 文档的类型名           | 现在（Q1） |
| 生成代码：其余                     | —                                                                            | 逐字节不变。B3～B7 每批都要用 golden 证明这一点                                                                                                                                             | 否                                | —          |
| peer 依赖                          | fetcher、fetcher-decorator、fetcher-eventstream、fetcher-openapi、wow-client | 不变，生成的代码需要它们。F18 只去掉生成器进程自己在运行时的导入                                                                                                                            | 否                                | —          |

建议把破坏性的几项（Logger、错误类别、类型名大小写）**全部在 9.2.0 做掉**：这个包名还没发布过，现在改零成本；发布以后再改，就只能等 10.0。按仓库规则，这几个提交都标 `!`，只进 `x.Y.0`，9.2.0 正好是。

### 5. 重构批次

每批一个 PR，都能单独合并，合并后门禁全绿：`lint`、`typecheck`、`test`、`build`、`package-check`，以及 `typescript-gate`、`typescript-contract-gate`（同源重新生成 integration-test 必须逐字节一致）。原则是**改产物的变更和重构分开**：B1、B2 有意改变行为，每项都列在 PR 里；B3～B7 必须逐字节不变，`expected/`、OpenAI 大文档 golden、integration-test、dashboard 的生成物都不许动。

| 批  | 内容                                                                                                                                                                                                                                                                                                                                                                          | 产物                                                                                   | 安全网（先补的放在前面）                                                                                                                                                                        | 人日 | 依赖                                          |
| --- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---- | --------------------------------------------- |
| B0  | **安全网**（#3355）：①公开面逐名清单（D29 形式）；②OpenAI 大文档 golden：提交 `expected/openai-spec/.wow-generator.json`（逐文件 SHA-256，格式同清单），失败时打印不一致的文件；因为现在要跑 3.5 分钟，先用 `WOW_GENERATOR_LARGE=1` 开关，B4 之后默认开启；③三份文档的警告文案 golden；④`scripts/bench.mjs` 输出各阶段耗时（不进 CI）；⑤单元测试按行为改名，去掉 `R2-xx` 前缀 | 不变                                                                                   | 新测试本身就是网：在当前 main 上生成一次，记下基线                                                                                                                                              | 2    | —                                             |
| B1  | **有意的产物修正**（`!`，#3382）：①流式命令客户端改用 wow-client 的 `COMMAND_STREAM_ENDPOINT`，即 `@api('', COMMAND_STREAM_ENDPOINT)`（F2，预设见 wow-client A4）；②类型名保留缩写（F3，Q1 通过后）                                                                                                                                                                           | 改：`expected/*` 的 commandClient、integration-test、dashboard 的生成物；OpenAI golden | 探针：用桩服务端发错误事件，断言流报 `WowError`；探针：`MCPListTools` 类型名保持原样，`tsc --strict` 通过；PR 里列出全部 golden 差异                                                            | 1.5  | B0                                            |
| B2  | **冻结公开面**（`!`，#3360）：`api/` 目录；删掉构造函数的 `project` 参数；`Logger` 瘦身（Q2）；新增 `output` 错误类别（Q3），把 F11 里的失败都改成 `GeneratorError`；更新 README、文档站程序化 API 页、AGENTS.md                                                                                                                                                              | 不变；CLI 在这些失败上的退出码改变                                                     | 公开面清单（B0）有意更新；`dist/*.d.ts` 不再出现 `ts-morph`（package-check 加一条断言）；CLI 退出码测试补齐循环引用、外部引用、清单损坏、路径越界                                               | 1.5  | B0                                            |
| B3  | **清理与依赖方向**（#3368）：删死代码（F17）；拆 `utils/` 为 `input/`、`openapi/`、`naming/`、`output/`、`emit/jsdoc`、`emit/imports`，纯搬移；拆开 `index ⇄ clis` 循环（F9）；固定排序器（F12）；加一条 lint 规则（`import/no-cycle` 或 dependency-cruiser，走 catalog）防止再出现循环                                                                                       | 逐字节不变                                                                             | golden 全部；新增确定性测试：在 `LANG=tr_TR.UTF-8` 和不同 cwd 下各生成一次，比较字节                                                                                                            | 1    | B0                                            |
| B4  | **一次写入的发射层（性能）**（#3379）：`ModuleBuilder` + `ImportRegistry`；模型和客户端改为先收集结构、最后插入一次；`Project` 加上 `skipAddingFilesFromTsConfig`，只用 tsconfig 的编译选项（F13）；默认开启 OpenAI golden                                                                                                                                                    | 逐字节不变                                                                             | OpenAI golden（873 个 schema）+ 两份 Wow golden + integration-test；bench 验收：OpenAI 文档 ≤ 20 秒（现在 214 秒），demo 不慢于现在                                                             | 3    | B3                                            |
| B5  | **`TypeResolver` 独立**（#3388）：从 `TypeGenerator` 抽出纯函数的类型解析，返回 `{ text, imports }`；删掉 `'\0client'` 伪造参数（F10）；别名判定查 `ImportRegistry`，去掉每个引用都扫全部 schema 的做法（F15）                                                                                                                                                                | 逐字节不变                                                                             | 先把 `schemaConstraints`、`compositionConstraints`、`enumConstConstraints` 这些探针的期望类型整理成表驱动的 `TypeResolver` 用例（纯函数、不经 ts-morph），然后再动代码；golden 全部             | 2    | B4                                            |
| B6  | **`OpenApiDocument` 与 Wow 约定**（#3397）：endpoint 只算一次，合并路径级参数；Wow 约定集中到 `wow/conventions.ts`（F6）；`resolveWowModel` 改成纯函数，不再改文档，文档覆写显式放在模型里（F7）                                                                                                                                                                              | 逐字节不变                                                                             | 先补 `wow/` 的契约测试：拿 demo、compensation 两份文档断言解析出的聚合、命令、事件、状态、字段和 resourceName（表驱动）；R2-30 的格式错误元数据探针；golden 全部；8.x 矩阵（契约 CI）           | 2    | B3（可与 B4、B5 并行，受 CPU 节奏限制时串行） |
| B7  | **分析模型与流水线**（#3403）：`analysis/` 产出 `GenerationModel`，各发射器只读模型（F5）；诊断改为返回值（F16）；删除 `GenerateContext`；`OutputStore` 取代 WeakMap 全局状态（F8）；先读配置后读文档，SIGINT 时不写清单（F20）；按行为重组单元测试（F14）                                                                                                                    | 逐字节不变                                                                             | 先补 `analysis` 的表驱动测试：给定文档断言 `GenerationModel`（方法名、参数顺序、请求体种类、返回种类、冲突报错）；regeneration 测试（清单、旧文件、手改文件）不变；警告文案 golden；golden 全部 | 3.5  | B5、B6                                        |

合计约 **16.5 人日**。

- **P0 路径**（首发的底线）：B0 → B1 → B2 → B3 → B4，约 9 人日。B3 排在 B4 前面，是因为搬移会让 B4 的差异小得多。
- **完整路径**（用户要求首发前完成）：再加 B5、B6、B7，约 7.5 人日。
- **顺序理由**：先有网（B0），再把有意的改变单独做完（B1、B2），这样后面每一批都能用「golden 一个字节都不许变」做判据。性能（B4）排在结构大改之前，因为它引入的发射层是 B5、B7 的落脚点，而且用户最先感受到的就是它。

#### 5.1 实施记录

**B1**（有意的产物修正，#3382）实施时定下的细节：

- 流式命令客户端（F2）：`@api('', COMMAND_STREAM_ENDPOINT)`，`COMMAND_STREAM_ENDPOINT` 和 `CommandRequest` 等类型一起从 `@ahoo-wang/wow-client` 导入（生成器里是 `decorators.ts` 的 `COMMAND_STREAM_ENDPOINT_METADATA`）。命令客户端文件不再导入 `ContentTypeValues` 和 `@ahoo-wang/fetcher-eventstream`。普通 API 客户端的 SSE 方法仍用 `JsonEventStreamResultExtractor`：它们不是 Wow 命令，事件名不是命令阶段，套 Wow 的提取器会把正常事件当错误。
- 证明它：①生成器探针（`test/probes.test.ts`「streaming command clients」）把生成的 `OrderStreamCommandClient` 转译后真跑，用桩 `fetch` 回一条 `SENT` 事件加一条 `CommandValidation` 错误事件，断言流交出 `SENT` 后以 `WowError`（`errorCode`、`errorMsg` 取自 `ErrorInfo`）报错；把生成器临时改回 `JsonEventStreamResultExtractor` 时它失败（错误事件被当成第二条数据，`stage` 为空），证明守得住。②integration-test 对同源 example-server 用生成的 `CartStreamCommandClient` 发一个校验失败的 `addCartItem`，断言没有数据行、流以 `CommandValidation` 的 `WowError` 结束并带 `bindingErrors`。它沿用 `wowErrors.test.ts` 的 `expectStreamFailure`：CI 的同源服务端在错误事件刷出前就断开响应时，流以无数据、无错误结束，也算通过；所以确定性的证明在①，②证明真实服务端上错误事件不会被当成数据。
- 类型名保留缩写（F3，Q1）：规则落在 `naming.ts` 的 `toTypeIdentifier`，按段判断：以大写字母开头、只含字母数字和 `$`（不含任何分隔符）、且是合法标识符的段原样保留，其余段照旧 `pascalCase`。`resolveModelInfo` 和 `resolveClassName`（聚合类名）都经过它；`pascalCase`、`camelCase`、`upperSnakeCase` 本身不变，所以方法名、枚举成员、端点常量、限界上下文常量不变。
- 实测改名：OpenAI 文档 873 个 schema 里 **49** 个改名（计划里的 52 是估算），全部是缩写：含 `MCP` 的 41 个（如 `McplistTools` → `MCPListTools`、`RealtimeMcphttperror` → `RealtimeMCPHTTPError`、`ToolChoiceMcp` → `ToolChoiceMCP`）、以 `GA` 结尾的 4 个（`RealtimeSessionCreateRequestGa` → `RealtimeSessionCreateRequestGA` 等）、`FineTuneDPO*` 2 个、`OpenAIFile`、`ModerationImageURLInput`。demo、compensation 两份 Wow 文档 0 个；example-server、wow-api、compensation-api 的类名里没有连续大写，integration-test 与 dashboard 的类型也不变。
- 产物差异（逐文件）：`expected/demo-spec/example/{cart,order}/commandClient.ts`、`expected/compensation-spec/compensation/execution_failed/commandClient.ts`（导入与 `@api` 两处），同样三份提交在 `typescript/integration-test/src/generated/`、`compensation/dashboard/src/generated/` 里，连同各自清单的哈希；OpenAI golden 的 `types.ts`、`FilesApiClient.ts`（`OpenAIFile`）、`RealtimeApiClient.ts`（`RealtimeSessionCreateRequestGA`）。其余文件逐字节不变，警告 golden 不变。
- 迁移说明：TS 迁移指南（中英）第 4 步加三条：流式命令客户端的 `WowError`、类型名缩写、B4 带来的「tsconfig `include` 覆盖输出目录时，输出目录里手写的 `.ts` 不再被生成的 index 再导出」。生成物参考页同步改了 `index.ts` 的说明、命名规则和流式客户端；错误处理指南里「生成的流式客户端不报错」那段改为现在的行为。

**B2**（公开面冻结，#3360）实施时定下的细节：

- `src/api/` 放公开类型和值（`options.ts`、`configuration.ts`、`logger.ts`、`errors.ts`），不导入包里的其他模块；`CodeGenerator` 搬到 `src/pipeline/codeGenerator.ts`，`src/index.ts` 只做再导出。`utils/clis.ts` 改为从 `pipeline/` 导入，`index ⇄ clis` 的运行时循环（F9 的一半）随之消失。
- 测试接缝：构造函数只收 `options`。测试用 `test/support/generation.ts` 的 `createCodeGenerator(options, project)`，它把 project 放在内部符号 `PROJECT_SEAM` 下传进去；这个符号不从包导出，声明里也只是 `unique symbol`，不引用 ts-morph。
- 「公开声明不引用 ts-morph」的断言放在包自己的 `scripts/verify-package.mjs`（构建时跑，CI 的 build 也跑），而不是仓库级的 `package-check.mjs`：它从 `dist/index.d.ts`、`index.d.cts` 沿相对导入走一遍可达的声明文件，断言没有一个导入 `ts-morph` 或 `@ahoo-wang/fetcher-openapi`。`dist/` 里内部模块的声明照旧生成，但从入口走不到。
- `Logger` 四个方法的 `ConsoleLogger` 映射：`debug` 沿用原 `info` 的符号，只在 `verbose` 输出；`info` 沿用原 `success` 的 `✅`，`normal` 起输出。`normal`、`quiet` 下的 CLI 输出逐字不变；`verbose` 下原 `progress` 行的 `🔄` 和按层级缩进没有了，计数行写成 `[i/n] …` 放进 `debug`。
- `output`（退出码 5）覆盖：清单不是 JSON 或形状不对、清单条目越出输出目录、写文件路径越界、写入或删除失败（消息为 `Cannot write <path>: <原因>`，原错误放在 `cause`）。组件循环引用、外部 `$ref` 归入 `specification`。tsconfig 读不到仍是构造函数里的普通 `Error`（退出码 1），按 F20 留给 B7。
- `SchemaDocs` 不加进公开面：`GeneratorOptions['schemaDocs']` 已能引用它，按「公开面最小」不多导出一个名字。
- 覆盖率：`vitest.config.ts` 排除 `test/**`、`scripts/**`，只量 `src`。重新测得语句 97.53、分支 93.14、函数 99.27、行 98.44，门槛定为 97 / 92.5 / 98.5 / 98。

**B3**（清理与依赖方向，#3368）实施时定下的细节：

- 目录：`utils/` 整个拆掉，纯搬移，函数体不动。`clis.ts` → `cli/runGenerate.ts`；`resources`、`parsers`、`configuration` → `input/`；`components`、`references`、`operations`、`responses`、`schemas` → `openapi/`；`naming.ts` → `naming/naming.ts`；`typeOnlyImports`、`verification` → `finalize/`；`logger.ts`（`WarningCounter`）→ `pipeline/warningCounter.ts`。`sourceFiles.ts` 按职责拆成三份：清单、所有权、路径防护、落盘 → `output/generatedFiles.ts`；导入 → `emit/imports.ts`；JSDoc → `emit/jsdoc.ts`。模块级 `WeakMap` 原样搬进 `output/`，按计划留给 B7 的 `OutputStore`。
- 桶文件：删掉 `utils/index.ts`；新目录都不设桶文件，直接导入模块本身，免得桶文件把不相干的模块连成环。`aggregate/`、`model/`、`client/` 的桶文件现在不成环，留给 B7 随分析层一起重组。测试跟着 `src/` 搬到同名目录，`sourceFiles.test.ts` 拆成 `output/generatedFiles`、`emit/imports`、`emit/jsdoc` 三份；原先 mock 整个 `utils` 桶的测试改为 mock 具体模块。
- 循环（F9）：`pipeline → utils 桶 → clis → pipeline` 的运行时循环随 `cli/` 独立而消失；类型层 `utils/sourceFiles → model` 变成 `emit/imports → model/modelInfo`（仅类型），文件级已无环（含类型导入在内，逐文件的强连通分量为空）。目录级还剩 `emit ⇄ model`：`TypeGenerator` 仍在 `model/` 里直接写 ts-morph，B4、B5 把发射部分移进 `emit/` 后消失。
- 防回归的 lint：选 `eslint-plugin-import-x`（进 catalog），不选 dependency-cruiser：前者跑在已有的 `pnpm lint` 里，CI 不用加步骤。`import-x/no-cycle` 拦值导入成环；它按设计跳过 `import type`，所以再用 `import-x/no-restricted-paths` 按层划区（含类型导入）：`api/` 不导入包内其他模块，`naming/` 只导入 `api/`，`openapi/` 只导入 `api/`、`naming/`，`input/` 只导入 `api/`、`naming/`、`openapi/`，`output/`、`finalize/` 只导入 `api/`；除入口和 `cli/` 外谁都不许导入 `pipeline/`，除 `cli.ts` 外谁都不许导入 `cli/`。另用 `no-restricted-syntax` 禁止 `src/` 里调用 `localeCompare`。
- 排序（F12）：`naming/order.ts` 的 `compareNames` 固定用 `Intl.Collator('en-US')`，两处 `localeCompare`（endpoint 排序、桶文件导出名）都改用它。CI 的 `LANG=C.UTF-8` 下 ICU 用根排序规则，英语没有定制，和 `en-US` 相同，所以字节不变。
- 确定性测试：`test/determinism.test.ts` 在子进程里跑构建好的 CLI（ICU 只在进程启动时读 locale），先断言子进程确实是 `tr-TR`；再拿一份 `ItemsList`、`index` 两个操作的小文档，在 `en_US.UTF-8`、`tr_TR.UTF-8` 下从不同的工作目录各生成一次，要求逐字节相同；最后在 `tr_TR.UTF-8` 下从上一级目录生成 demo 文档，和 `expected/demo-spec` 逐字节比较。把 `compareNames` 临时改回 `localeCompare` 时，第二条失败，证明它确实守得住。
- 死代码（F17）：删 `EventStreamSchema`、`DomainEventSchema`（整个 `aggregate/types.ts`）、`resolveEnumMemberName`、`isUnion`（连同 `UnionSchema`）、`COMPONENTS_HEADERS_REF`；`isIgnoreCommandClientPathParameters` 的 `tagName` 参数、`resolveParameters` 的 `tag` 参数、`ApiClientGenerator` 未用的 `defaultParameterRequestType`；`addImport` 的 `exited` 改为 `exists`；`ClientGenerator` 里只打日志的循环。`tsconfig.json` 打开 `noUnusedLocals`、`noUnusedParameters`，防止再积累。`vitest.config.ts` 排除 stories 的那条 B2 已经删了。
- 测试的类型检查：包原来没有 `test:type`，测试从没被类型检查过，试跑有 6 处错误。新增 `test/tsconfig.types.json`（覆盖 `src` 和全部测试文件）和 `test:type`，接在 `test`、`test:no-coverage` 后面，和 wow-client、wow-react 一样。修法：夹具补上必需的 `resourceName`；`cli.ts` 导出 `collect`，测试直接断言 `--header` 用的就是它，不再把 mock 参数强转成函数；测试接缝 `PROJECT_SEAM` 和 `SeamOptions` 移到 `pipeline/projectSeam.ts`，`createCodeGenerator` 先写成有类型的 `SeamOptions` 再传进去（放在单独模块里，`codeGenerator.d.ts` 就不会因为导出 `SeamOptions` 而引用 ts-morph）；收尾步骤抽成 `finalize/finalize.ts` 的 `finalizeSourceFiles(files, logger)`（§3.2 本来就把它划给 `finalize/`），`commandAliases` 测试改调它，不再访问 `CodeGenerator` 的私有方法；删掉三个未用的变量和导入。
- 不做的：F18（生成器进程为几个常量运行时导入 `wow-client`、`fetcher`）不在任何一批里，本批也不做：`ResourceAttributionPathSpec` 属于 Wow 约定，随 B6 的 `wow/conventions.ts` 一起收；`combineURLs`、`ContentTypeValues` 随 B6 的 `OpenApiDocument` 一起换成本地实现，那时再由 golden 证明字节不变。

**B4**（一次写入的发射层，#3379）实施时定下的细节：

- 发射层：`emit/moduleBuilder.ts` 的 `ModuleBuilder` 代表一个生成文件，发射器往里加 ts-morph 结构（`InterfaceDeclarationStructure` 连同 `properties`、`docs`，类连同构造函数和方法），导入记进 `emit/importRegistry.ts` 的 `ImportRegistry`；`ModuleSet` 按路径一个文件一个 builder。生成器通过 `GenerateContext.module(path)` 拿 builder，不再碰 `SourceFile`。流水线在客户端生成之后调 `context.modules.build()`，每个文件一次 `addStatements` 写入（导入和语句同一次），然后才是 index 文件和收尾。模型和三种客户端都改了，按计划 §3.3。
- 逐字节不变靠三处对齐 ts-morph「一条一条加」时的打印结果，每一处都有测试（`test/emit/moduleBuilder.test.ts` 把五种语句两两组合、有无导入，逐一和逐条添加的文本比较）：①语句之间的空行：逐条添加时类型别名接类型别名、变量语句接变量语句只换行，其余都空一行，而一次打印的规则不同（看上一条是否以 `}` 结尾），所以 `build()` 在需要空行的地方插入写空行的 writer；②逐条 `addMember` 的枚举每个成员后都有逗号，结构打印只在成员之间加，于是命令端点和事件标题这两个枚举的成员用 `membersWithTrailingComma`；③结构打印把 `indexSignatures` 放在属性前面，而原来是先加属性再加索引签名，所以索引签名写成排在属性后面的成员（`indexSignatureMember`，打印文本相同）。
- 导入别名：原来的 `setAlias` 只改导入、不改已写出的用法，所以在内存里记下别名、之后的引用用别名，得到的文本相同；判定时的保留名（全局名、同目录的模型名、文件里其他导入的本地名）照旧，只是从 `ImportRegistry.localNames()` 读。每个引用扫一遍全部 schema 的做法（F15）按计划留给 B5。
- 性能（bench，同一台机器，load average 6～8）：OpenAI 文档墙钟 205.06 → **1.54 秒**，user CPU 282.88 → 2.61 秒，峰值 RSS 747 → 592 MB；模型阶段 203.20 → 0.04 秒，新的「write modules」阶段 0.11 秒。demo 0.43 → 0.30 秒。bench 多了一个阶段 `write modules`（日志 `Writing generated modules`）。
- OpenAI golden 默认开启：去掉 `WOW_GENERATOR_LARGE` 开关和 `test:large` 脚本，`test/openaiGolden.test.ts` 随 `pnpm test` 跑，CI 也跑；生成超时从 15 分钟改成 60 秒，退回平方复杂度的发射方式会直接超时失败。接受有意的改动：`vitest run test/openaiGolden.test.ts -u`。
- F13：`Project` 加 `skipAddingFilesFromTsConfig: true`，只用 tsconfig 的编译选项。附带的行为：输出目录里不是本次生成的 `.ts` 文件（手写的，或上次生成后被改过、这次不再生成的）不再进入 ts-morph 项目，所以也不会被生成的 index 再导出。以前这一点取决于 tsconfig 的 `include` 是否恰好覆盖输出目录（不传 tsconfig 时本来就不导出，`regeneration` 测试守的就是这个）；现在两种情况一致。goldens 与 integration-test 的输出目录里只有生成文件，字节不变。
- 依赖方向：`ModelInfo` 接口移到 `naming/modelInfo.ts`（`model/modelInfo.ts` 再导出，调用方不变），`emit/` 不再导入 `model/`，B3 留下的目录级环 `emit ⇄ model` 消失，src 的目录级强连通分量为空。`eslint.config.js` 加一条 `leaf('emit', ['api', 'naming'])`，故意加一条 `emit → model` 的导入会报错，已验证。
- 顺带（F19 的一半）：`createClientFilePath`（返回 `SourceFile`）改成 `clientModulePath`（只返回路径）；`stateAggregatedTypeNames` 的拆分留给 B7。`addJSDoc`、`addSchemaJSDoc`、`addMainSchemaJSDoc` 改为往结构的 `docs` 里追加。
- 测试：直接驱动 `TypeGenerator` 的约束测试改为给它一个 `ModuleBuilder`，生成后 `build()` 再做类型检查；驱动生成器的测试在 `generate()` 后调 `context.modules.build()`；`typeGenerator`、`decorators`、`queryClientGenerator`、`emit/imports`、`emit/jsdoc` 的单元测试改为断言结构。覆盖率 97.68 / 93.72 / 99.53 / 98.63（门槛 97 / 92.5 / 98.5 / 98）。

每批的收尾：本地跑改到的包的 `lint:check`、`typecheck`、`test`（`vitest --maxWorkers=2`）、`build`，重活包进 `heavy.sh`；PR 描述写明「产物是否逐字节不变」，改了的列出差异；合并后更新 `typescript/MIGRATION.md` 的进度。

**B5**（`TypeResolver` 独立，#3388）实施时定下的细节：

- 模块：`src/types/typeResolver.ts`，纯函数。入口 `resolveType`，另有模型声明要用的 `resolveAdditionalProperties`、`resolveAdditionalPropertyType`、`resolveRequiredAdditionalPropertyType`、`resolveMapValueType`，都是 `(schema, scope) → { text, imports }`；`requiresAdditionalPropertiesIntersection`、`resolveLiteral` 不涉及导入，直接返回结果。`TypeGenerator` 只剩声明（interface、enum、type alias、index signature），它和 API 客户端都在每次解析后把 `imports` 交给 `ImportRegistry.apply`，再解析下一个类型。
- 作用域 `TypeScope`：`context`（一次生成共用一个 `TypeContext`：components、引用 → `ModelInfo`、每个路径声明了哪些模型名）、`owner`（名字不许被导入占用；带 `path` 表示模型，同路径的引用不导入、同路径的模型名也不许被导入占用；不带 `path` 表示 API 客户端，一律导入）、`specifierOf`（模型的导入说明符，由 `emit/imports.ts` 的 `modelModuleSpecifier` 给出）、`imports`（模块已有的导入，只读）。
- 别名判定逐字节不变：一次解析先拷一份模块已有的导入，在这份拷贝上照旧逐个引用判定（已有别名就用；名字被全局名、owner、同路径模型名或其他导入占用时加 `_` 前缀），返回的 `imports` 按首次请求的顺序带上别名；调用方在下一次解析前 `apply`，所以后一个引用看到的状态和原来逐条 `addImport` 时相同。解析本身从不改动模块的导入（有测试）。
- F10：API 客户端不再伪造 `{ name: className, path: '\0client' }` 和空 schema 去借 `TypeGenerator`，改为 `owner: { name: className }`（无 `path`）的作用域；原来的魔法路径正是为了「永远不等于任何模型路径」，现在由「没有 path」直接表达。`addImportModelInfo` 随之删除。
- F15：`TypeContext.namesAt(path)` 第一次调用时把全部 schema 按路径分组一次，之后查表；原来每解析一个跨路径引用就对全部 components 重算一遍 `resolveModelInfo`。`GenerateContext.types` 持有这一个 context，模型和 API 客户端共用。`schemaConstraints` 等直接构造 `TypeGenerator` 的测试不传 context 时，构造函数自己建一个，签名向后兼容。
- 依赖方向：`types/` 只导入 `api/`、`naming/`、`openapi/`、`emit/`（`emit/jsdoc` 的纯文本函数 `jsDoc`、`schemaJSDoc`，给内联对象类型的属性写注释），`eslint.config.js` 加了 `leaf('types', ['api', 'naming', 'openapi', 'emit'])`。引用 → 模型名的规则（含 Wow 类型映射）在 `model/modelInfo.ts`，由 `documentTypeContext` 注入，`types/` 不反向依赖 `model/`，目录级仍无环。字符串字面量仍用 ts-morph 的 `CodeBlockWriter.quote` 转义，只当字符串函数用，保证字节不变；换成自己的转义留给以后，需要单独的 golden 证明。
- 安全网先于改动：`test/types/typeResolverCases.ts` 列了 88 个用例（与 B1 合并后 92 个，见下条）（原始类型、类型数组、可空、引用与别名的各种冲突、组合与 discriminator、const、enum、map、对象与索引签名、其余入口），在未改动的 main 上用旧的 `TypeGenerator` 录下每个用例的文本和导入，存为 `expected/type-resolver.json`（prettier 不管 `expected/`）；`test/types/typeResolver.test.ts` 用新的纯函数跑同一张表并逐字比对，不经 ts-morph。`test/model/typeGenerator.test.ts` 里用 `(generator as any).resolveType` 驱动私有方法的 20 个用例删除，由这张表取代。
- 与 B1 合并（B1 #3382 先合入 main）：B1 的缩写规则在 `naming.ts` 的 `toTypeIdentifier`，经 `resolveModelInfo` 注入 `TypeContext`，纯解析器自动沿用；流式命令客户端在 `commandClientGenerator.ts`，B5 不涉及。原有 88 个用例的 schema 名里没有缩写，所以用 main（B1 之后）上未改动的旧 `TypeGenerator` 重录，结果与 B1 之前的录制逐字节相同。为了让这张表也守住 B1，加了 4 个用例（`MCPListTools`、另一路径的 `api.OpenAIFile`、API 客户端同时引用两者、带分隔符的 `mcp_http_error`），共 92 个；golden 用 B1 之后的旧 `TypeGenerator` 重录。同一张表在 B1 之前录出 `McplistTools`、`OpenAifile`，之后是 `MCPListTools`、`OpenAIFile`，导入名随之变化；`mcp_http_error` 两边都是 `McpHttpError`。差异只有这些。
- 产物：`expected/`、OpenAI golden、integration-test、dashboard 的生成物逐字节不变。
- 性能（bench，同一台机器，load average 8～16）：OpenAI 文档的「客户端」阶段 0.47 → **0.02 秒**，墙钟 1.56 → 1.44 秒；demo 0.37 → 0.33 秒。剩下最大的一块是收尾（格式化、整理导入、类型导入、校验，约 0.8 秒）。

**B6**（`OpenApiDocument` 与 Wow 约定，#3397）实施时定下的细节：

- 安全网先于改动（单独一个提交，在未改动的 main 上录制）：`test/wow/wowModelContract.test.ts` 把 demo、compensation 两份文档解析出的 Wow 模型（限界上下文、聚合 tag、每个聚合的 resourceName、state、fields、命令的方法/路径/路径参数/body/summary、事件的名字/标题/body、元数据借给 schema 的注释、警告）写成 `expected/wow-model/{demo,compensation}.json`，另有两行手写的抽查（demo 的 `sales-order`、compensation 的 `execution_failed`）；「借给 schema 的注释」在录制时用「解析前后 schema 的 title/description 之差」求出。同一个文件再用 `schemaDocs: 'full'` 生成两份文档，逐文件 SHA-256 记为 `expected/wow-model/*-full-docs.json`：`full` 会把整份 schema 以 JSON 写进注释，旧解析器改写文档后多出的键和键序都会反映在里面，默认的 `summary` golden 看不到这一层。`test/wow/malformedMetadata.test.ts` 是 R2-30 的表：8 种格式错误的元数据（state 不是 `$ref`、事件列表不是 `$ref` 数组、事件流没有 `anyOf`、领域事件缺 `name.const`、内联事件 body、count 没有请求体、`x-wow-query-fields` 不是引用、8.11 之前的 condition 没有 `field` 引用）逐条断言 `specification` 类别和整句消息，外加命令响应引用成环、8.11 之前的 condition 正常读取、非聚合 tag 的错误元数据被忽略，以及 3 种只缺元数据（无 state、无 fields、命令 body 不是引用）时跳过并告警。两张表在旧解析器上先跑通，改完后不动一个期望。
- `openapi/document.ts`：`OpenApiDocument { openAPI, components, endpoints }`，`openApiDocument(openAPI)` 只算一次 endpoint（已合并路径级参数，按 operationId、路径、方法排序）。原来一次生成算三遍（解析器构造函数、`build()`、`ApiClientGenerator.groupOperations`），`resolveApiTags` 还自己用 `extractOperations` 遍历一遍、不合并路径级参数；现在四处都读同一份 `document.endpoints`。收集 tag 不看参数，所以合并与否不影响产物；遍历顺序从文档顺序变成排序后的顺序，只影响那张 tag 表的插入顺序，而它只被 `has`/`get` 读，产物不变。`GenerateContext` 带上 `document`（B7 删掉 `GenerateContext` 时由分析层接手）。
- `wow/`：`conventions.ts` 收齐全部 Wow 约定：`x-wow-context-alias`、聚合 tag 形状、命令 operationId 形状、`wow.command.send`、`wow.CommandOk`、三个 operationId 后缀、`x-wow-query-fields`、快照路由正则、最低服务端版本；Wow 自带 schema 的判定和聚合派生类型后缀（原 `modelGenerator.ts` 的 `isWowSchema`）；到 wow-client 的类型映射、legacy 集合和带 `filter` 的查询映射（原 `model/wowTypeMapping.ts`，删除；`resolveModelInfo` 改为调 `wowTypeOf`）；API 客户端忽略的 tag `wow`、`Actuator`；归属路径参数 `tenantId`、`ownerId`；资源归属推断 `inferPathSpecType`（原 `client/utils.ts`）。`model.ts` 放聚合类型（原 `aggregate/aggregate.ts`）和 `WowModel`；`resolveWowModel.ts` 是解析器。`aggregate/` 整个删掉。`eslint.config.js` 加 `leaf('wow', ['api', 'openapi'])`：`wow/` 不认识命名、模型和发射；`model/modelInfo.ts` 反过来导入 `wow/conventions.ts`。`isWowSchema` 要模型名时才算（传一个取名函数），和原来一样只在 key 本身判断不了时才调 `resolveModelInfo`。
- F7：`resolveWowModel(document): WowModel` 是纯函数，契约测试和单元测试都断言调用前后文档深相等。`WowModel { contextAlias, contexts, aggregateTags, schemaDocOverrides, warnings }`。原来 `commands()` 对命令 body 做 `title = title || summary`、`description = description || description`，`events()` 对事件 body 做 `title = title || 事件标题`；现在这些赋值记进 `schemaDocOverrides`（按 schema key），`lend` 读「已记下的值，否则 schema 自己的值」再做同样的 `||`，所以同一 body 被多个命令共用时仍是第一个非空的 summary 胜出。为了逐字节不变，覆写**逐次记下每一次赋值**，包括值为 `undefined` 或与原值相同的：`withDocOverride(schema, override)` 是 `{ ...schema, ...override }`，schema 已有的键保持原位，缺的键按赋值顺序追加在后面，值为 `undefined` 的键在 JSON 里省略——和原地改写后 `JSON.stringify` 的结果完全一样（有单测构造了「命令先赋 `title = undefined`、事件后赋标题」这一最刁钻的键序）。只有模型的注释读覆写后的 schema（`ModelGenerator` 算出 `docSchema` 传给 `TypeGenerator`），类型解析仍读文档里原样的 schema，免得复制出来的对象改变按对象身份去重的逻辑。
- 诊断改为返回值的第一步：解析器不再拿 `Logger`，警告放进 `WowModel.warnings`，流水线紧接着依次 `logger.warn`。原来命令的警告在构造函数里打、聚合的警告在 `resolve()` 里打，两者之间没有别的日志，所以顺序和文案都不变（警告 golden 不变）。
- F18：生成器进程不再在运行时加载任何 `@ahoo-wang/*` 包。`combineURLs` 换成 `naming/paths.ts` 的 `combinePaths`（逐字移植；`test/naming/paths.test.ts` 用同一张表同时断言两者结果相同，fetcher 只作为开发依赖在测试里加载）；`output/` 因此允许导入 `naming/`。`ContentTypeValues` 换成 `openapi/responses.ts` 的 `APPLICATION_JSON`、`TEXT_EVENT_STREAM`。`ResourceAttributionPathSpec` 换成 `wow/conventions.ts` 的 `TENANT_PATH_PREFIX`、`OWNER_PATH_PREFIX`，测试断言它们等于 wow-client 枚举的值。`scripts/verify-package.mjs` 加第 6 条：`dist` 里每个 `.js`/`.cjs` 都不许 `import`/`require` `@ahoo-wang/*`，构建时就拦住回退。peer 依赖不变（生成的代码需要它们），类型导入照旧（编译后消失）。
- 测试：`test/aggregate/` 并入 `test/wow/`。原 `aggregateResolver.test.ts`（29 个用例，mock 掉 `openapi/*` 后直接调私有方法）和 `aggregateResolver.integration.test.ts` 删除，由 `resolveWowModel.test.ts`（30 个用例，每个都是一份真实的小 Wow 文档改一处）取代；`aggregate/utils.test.ts`、`model/wowTypeMapping.test.ts` 和 `client/utils.test.ts` 里的 `inferPathSpecType` 合并成 `wow/conventions.test.ts`，并补了 `isWowSchema` 的表；`modelGenerator.test.ts` 里调私有 `isWowSchema` 的用例改调约定模块。新增 `openapi/document.test.ts`。覆盖率 98.03 / 94.64 / 99.57 / 98.95（门槛 97 / 92.5 / 98.5 / 98）。
- 文档：compat-debt 的两个标记路径、文档站 `wow-discovery`、`programmatic-api`（中英）里的源码链接和 `AggregateResolver` 字样、`MIGRATION.md` 的一处行号引用，随文件搬移更新。
- 产物：`expected/`、OpenAI golden、警告 golden、`type-resolver.json` 逐字节不变；integration-test、dashboard 的生成物由契约 CI 同源重新生成比对。

**B7**（分析模型与流水线，#3403）实施时定下的细节：

- 分析层（F5）：`analysis/analyze.ts` 的 `analyze(document, wow, config)` 返回 `{ model: GenerationModel, warnings }`，纯数据，不引用 ts-morph，也不引用原始 `Operation`（聚合部分借 `wow/` 的定义读出路径、参数和注释后就不再带着它）。`GenerationModel` 四块：`contexts`（别名、常量名、文件）、`models`（key、`ModelInfo`、文件、原样 schema、借来注释后的 `docSchema`、是否空消息体）、`aggregates`（命令客户端：路由枚举成员、方法名、命令类型名、body 模型、可省字段、路径参数；查询客户端：资源名、资源归属、state/fields 模型、事件标题与 body）、`apiClients`（tag、类名、文件、base path、方法：名字、参数、body 种类、返回种类、注释）。模型的「声明成 interface、enum 还是 type alias」仍由发射器按 schema 判断：它和类型解析交织，拆出去只会多一层镜像。
- 逐字节不变的关键是**解析顺序**：类型别名的判定取决于同一模块里谁先请求导入。所以 `ApiMethodModel.parameters` 按「path、query、header，各自文档顺序」排列（类型解析的顺序），发射器解析完再按「必需在前、body 按是否必需插在两段末尾」排出方法签名；发射顺序固定为限界上下文 → 模型 → 全部查询客户端 → 全部命令客户端 → API 客户端（按 tag 名排序），和原来各生成器的执行顺序一致。
- 目录：`emit/` 仍是写入工具（`ModuleBuilder`、`ImportRegistry`、导入说明符、JSDoc 文本），不认识模型；发射器放在新目录 `emitters/`（模型 `ModelEmitter`，由 `TypeGenerator` 改来；限界上下文；查询、命令、API 客户端；index 文件）。没有把发射器放进 `emit/`：`types/` 要用 `emit/jsdoc.ts` 的纯文本函数，发射器又要用 `types/`，放一起会让目录级出现 `emit ⇄ types` 的环。`eslint.config.js` 的层：`api → naming → openapi → wow → analysis`，`emit → types → emitters`（`emitters/` 读 `analysis/`）；另加 `no-restricted-imports`，`api/ input/ naming/ openapi/ wow/ analysis/` 不许导入 ts-morph，故意违反两条都已验证会报错。`model/modelInfo.ts` 搬到 `analysis/modelInfo.ts`，`client/utils.ts` 成了 `analysis/clientNames.ts`（`methodToDecorator` 去了 `emitters/decorators.ts`），模型文件与限界上下文文件的路径规则 `modelFilePath`、`boundedContextFilePath` 移到 `naming/paths.ts`。删掉 `GenerateContext`、`GenerateContextInit`、`Generator` 接口、`ModelGenerator`、`ClientGenerator` 以及 `client/`、`model/` 两个桶文件。
- 诊断即返回值（F16）：只有一种严重度（错误都是抛出的 `GeneratorError`），所以诊断就是一行文字，不另设 `Diagnostic` 类型。配置（`LoadedConfiguration.warnings`）、Wow 模型（`WowModel.warnings`）、分析（`Analysis.warnings`）、index 文件（`emitIndexFiles` 的返回值）各自返回；`CodeGenerator` 逐条 `logger.warn` 并计数，`WarningCounter` 删除。输入层仍用 `logger.debug` 记读了什么，那是进度不是诊断。
- `OutputStore`（F8）：`OutputStore.open(project, outputDir, last?)` 读清单（旧名兼容），把上次清单里的文件和上一个 store 写过或起草过的文件移出 project；`claim(filePath, directory?)` 防越界、登记所有权、首次领取时清空；`forgetStale()`；`commit(signal?)` 落盘、删除未改动的陈旧文件、写清单、删旧清单。`CodeGenerator` 保存上一次运行的 store，同一个实例重跑时由它带走草稿，这是原来 `WeakMap<Project, …>` 唯一承担的语义。
- F20：`generate()` 先 `resolveConfiguration` 再 `parseOpenAPI`，配置写错时不再先拉文档。tsconfig 读不到改为 `configuration` 类的 `GeneratorError`（退出码 3，原来是普通 `Error` 的 1），所以 PR 标 `!`。Ctrl-C：`generateAction` 不再 `process.exit(130)`，而是 abort 一个 `AbortController`，信号经内部符号 `SIGNAL_SEAM`（和 `PROJECT_SEAM` 一起放在 `pipeline/seams.ts`，原 `projectSeam.ts`）交给 `CodeGenerator`；流水线在每个 await 之后 `throwIfAborted()`，远程读取把它和超时合成一个 signal；`commit` 在写文件之前和写完之后各查一次，所以中断发生在写入期间时文件写完、陈旧文件不删、清单不动，清单永远不记录没跑完的一次。`runGenerate` 看到信号已 abort 就返回 130。`process.once` 只接第一次 Ctrl-C，第二次仍按默认行为立即结束进程。
- 行为差异（不影响产物）：配置和文档都有警告时，配置的警告现在排在前面；一份文档同时有两类规范错误时，分析阶段的错误（方法名冲突、`x-fetcher-method` 不合法）会先于发射阶段的类型解析错误（模型里的外部 `$ref`）报出。清单损坏与规范错误的先后不变：store 仍在分析之前打开。
- 逐字节不变的证明：`expected/`、OpenAI golden、警告 golden、`expected/wow-model/`（含 full-docs 哈希）、`type-resolver.json` 都没动；重新构建后跑确定性测试。另写了一次性的差分测试（不提交）：把 `origin/main` 的 `src/` 解出来，和本分支的 `CodeGenerator` 对 7 份文档（demo 带与不带配置、compensation、OpenAI、`skills/wow-generator/evals` 的三份）在 `summary`、`full` 两档下各生成一次，14 组的每个文件和每条警告都相同。
- 测试按行为重组（F14）：`test/goldens/`（原 `e2e`、`openaiGolden`、`determinism`）、`test/probes/`、`test/models/`、`test/analysis/`（新增，表驱动断言 `GenerationModel`：方法名、参数顺序、body 种类、返回种类、冲突报错、警告）、`test/emitters/`、`test/output/`（`OutputStore` 与 `regeneration`）、`test/cli/`、`test/pipeline/`。`test/support/emission.ts` 在内存里跑分析和发射，取代到处手拼的 `GenerateContext`。只测已删内部件的用例（构造函数透传、私有方法、mock 掉的生成器）换成行为用例；模型发射器里「属性已存在就改类型」的分支不可达（属性名在 JSON 里本就唯一，`resolvePropertyName` 只保留或加引号，不会让两个名字相同），随之删除。
- F19 的另一半：`stateAggregatedTypeNames()`（名字像查询，实际会生成全部 `boundedContext.ts`）拆成 `analyzeContexts`（要生成哪些限界上下文）和 `derivedTypeNames`（聚合派生、应当留给 wow-client 的类型名），两者都是纯函数。
- 慢测试：见 §7 的「B7 的测试耗时」。
- 覆盖率 98.39 / 95.22 / 100 / 99.16（门槛 97 / 92.5 / 98.5 / 98）；bench：OpenAI 文档墙钟 1.09 秒，demo 0.30 秒；阶段表改为 配置、解析、Wow 模型、分析、发射、index、收尾、落盘。

### 6. 待拍板的问题

**已定（2026-09-24）**：用户「按你推荐」，Q1～Q5 全部按下面的建议执行。原则是首发前重构到生产就绪，不留兼容债。批次按第 5 节推进，每做完一批就在第 5 节标上 PR 号；全部做完后，本页并入包的设计文档（B7 之后并入，即本页的附录）。

**Q1　类型名是否保留缩写（R2-31），9.2.0 就改？**
推荐：**改**。规则是：名字段本身已是合法标识符、且以大写开头的，原样保留（`MCPListTools` 保持不变）；只有含分隔符或非法字符的段，才切词后转成 PascalCase。只动类型名，方法名、枚举成员、端点常量的规则不变。Wow 文档零变化（demo、compensation 两份都是 0 个），非 Wow 文档会有改名（OpenAI 52/873）。首发前改零成本，发布后只能等 10.0。

**Q2　`Logger` 在 9.2.0 瘦成 `debug`/`info`/`warn`/`error` 四个必需方法？**
推荐：**瘦**。`progress`、`progressWithCount` 的缩进层级和可选的 `warn`，都是为 fetcher-generator 时代留的兼容，新包名没有要兼容的用户。现在的 `info` 调用改为 `debug`，`success` 改为 `info`，CLI 输出不变。

**Q3　新增错误类别 `output`（退出码 5）？**
推荐：**加**。清单损坏、路径越界、写入失败是用户能处理的环境问题，现在报成退出码 1 加「请提 issue」。文档里的循环引用、外部引用归入 `specification`（4）。

**Q4　9.2.0 提供插件或自定义发射器 API 吗？**
推荐：**不提供**。先把阶段边界做成内部接缝（`GenerationModel`、`ModuleBuilder`），等有具体需求（例如生成 React hooks）再在 9.x 小版本里新增。现在就公开，等于把还没用过的内部模型冻住。

**Q5　外部 `$ref` 打包（R2-21）放在哪？**
推荐：**首发后，9.3 以新增方式做**。在 `input/` 里接现成的打包库（按「优先用第三方库」，走 catalog），加载阶段就把外部引用并成本地组件，后面的阶段不用改。首发前只把现在的报错改成 `specification` 类别，报错仍然提示先打包（B2）。

### 7. 性能基线

环境：本机 macOS，Node 24.11，`/private/tmp/wow-heavy/heavy.sh` 串行执行，测量时 load average 约 5。命令：`node dist/cli.js generate -i <spec> -o <out> -t tsconfig.json --verbose`，tsconfig 的 `include` 只含输出目录。

| 文档                             | schema | 操作 | 输出                                          | 墙钟    | user CPU | 峰值 RSS |
| -------------------------------- | ------ | ---- | --------------------------------------------- | ------- | -------- | -------- |
| `test/demo.spec.json`（Wow）     | 93     | 108  | 14 个文件                                     | 0.64 s  | 1.17 s   | 417 MB   |
| `test/openai.spec.yml`（非 Wow） | 873    | 219  | 34 个文件，根 `types.ts` 21,881 行，20 条警告 | 213.7 s | 284.8 s  | 883 MB   |

OpenAI 文档的 `--cpu-prof` 剖析（这次运行共 231.9 秒）：

| 位置（含子调用）                                          | 秒    | 占比 |
| --------------------------------------------------------- | ----- | ---- |
| `ModelGenerator.generate`                                 | 193.2 | 83%  |
| └ `TypeGenerator.processInterface`                        | 165.9 | 72%  |
| 　└ `addPropertyToInterface`（逐属性插入 + JSDoc）        | 151.9 | 66%  |
| ts-morph `doManipulation`                                 | 133.2 | 57%  |
| ts-morph `addJsDoc`                                       | 91.9  | 40%  |
| `optimizeSourceFiles`（格式化、整理导入、类型导入、校验） | 0.7   | 0.3% |

自耗时排前面的：TypeScript `scan` 37.4 s、GC 32.5 s、`doJSDocScan` 16.9 s、`iterateCommentRanges` 6.9 s，都是在反复解析同一个不断变大的文件。结论：瓶颈是发射方式（逐条增量改），不在收尾。B4 改成每个文件插入一次以后，工作量应当和产物大小成线性，验收门槛定为 ≤ 20 秒，B0 的 bench 负责复测。

B0（#3355）用 `scripts/bench.mjs` 在当前 main 上复测（load average 约 6～7）：OpenAI 文档墙钟 203.1 秒，user CPU 281.6 秒，峰值 RSS 792 MB，模型阶段 201.3 秒（99.1%）；demo 0.45 秒。

各阶段时间点（`--verbose`）：解析文档和聚合都在同一秒内完成；模型生成从 16:25:57 到 16:29:29；客户端、index、收尾、保存合计约 1 秒。

B4（#3379）之后同一台机器复测（load average 约 6）：OpenAI 文档墙钟 **1.54 秒**（之前 205.06 秒），user CPU 2.61 秒，峰值 RSS 592 MB；各阶段：解析 0.25、模型 0.04、客户端 0.47、写入模块 0.11、index 0.12、格式化/导入/校验 0.55、保存 0.01 秒。demo 0.30 秒（之前 0.43 秒）。剩下最大的两块是客户端（API 客户端每解析一个引用扫一遍全部 schema，F15，归 B5）和收尾。

B7（#3403）的测试耗时。CI 的 `Unit / wow-generator` 在 B6（#3397）上：Node 22 整个 job 3 分 38 秒，其中 vitest 175.3 秒，`schemaConstraints.test.ts` 173.8 秒、`probes.test.ts` 155.5 秒；Node 24 整个 job 2 分 08 秒，vitest 96.2 秒。关掉覆盖率时这两个文件本地只要 7～8.5 秒，所以代价在覆盖率插桩：V8 的精确覆盖连 TypeScript 编译器本身也插桩，本地实测一次生成从约 100 毫秒变成约 450 毫秒、一次类型检查从约 90 毫秒变成约 550 毫秒。两个文件的共同点是**每次检查都新建一个 TypeScript 程序**：`schemaConstraints` 每个模型一个新的 ts-morph 项目，探针每次生成一个新的生成器项目、再各做一两次 `ts.createProgram`，每个程序都要先把 `lib.dom.d.ts` 和 `node_modules` 里的声明重新解析一遍。修法（断言一条不删）：`test/support/models.ts` 让一个测试文件的模型共用一个内存项目，每次调用一个目录、每个测试结束后移除，插桩下一个模型从约 400 毫秒降到约 4 毫秒；探针经 `runGenerate` 的 seams 参数在同一个项目里生成，`typeCheck` 按编译选项缓存库文件和 `node_modules` 的声明（约 550 → 65 毫秒），同一文件里多个探针共读的 Wow 文档只生成一次；再按行为拆成 `test/models/` 四个文件、`test/probes/` 五个文件。本地（覆盖率，`--maxWorkers=2`）：两个文件 36.9 秒和 35.5 秒 → 九个文件各 0.5～2 秒，最长的文件变成 `output/regeneration`（6.1 秒）；vitest 墙钟 58.2 → 25.6 秒，各文件耗时之和 105.0 → 36.8 秒。CI（#3403 的第一次运行）：Node 22 整个 job 3 分 38 秒 → 1 分 13 秒，vitest 175.3 → 47.2 秒（各文件之和 481.7 → 105.1 秒）；Node 24 整个 job 2 分 08 秒 → 1 分 50 秒，vitest 96.2 → 73.2 秒（之和 262.3 → 169.4 秒）。Node 24 那台 runner 这次整体偏慢（没动过的 OpenAI golden 8.4 → 15.0 秒）；两边最长的文件都变成 `output/regeneration`（17.6 秒、29.1 秒），它每次运行新建生成器、模拟独立的进程，这是它要测的东西，没有共享项目。
