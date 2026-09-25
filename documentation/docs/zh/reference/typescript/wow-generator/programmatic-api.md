---
title: '程序化 API'
description: '程序化 API — @ahoo-wang/wow-generator'
---

# 程序化 API

包根导出 `CodeGenerator`、`DEFAULT_CONFIG_PATH`、日志器 `ConsoleLogger` 与 `SilentLogger`、`GeneratorError` 与 `EXIT_CODES`，以及类型 `GeneratorOptions`、`GenerationResult`、`GeneratorConfiguration`、`ApiClientConfiguration`、`Logger`、`ConsoleLoggerOptions`、`LogLevel`、`GeneratorErrorKind`。可执行文件单独通过 `wow-generator` binary 暴露。不要从未公开子路径导入内部 resolveWowModel、analyze、各发射器、OutputStore、setupCLI 或解析辅助函数。ESM `import` 与 CommonJS `require` 均可使用。

## CodeGenerator

| 成员                   | 参数/默认值                | 返回与效果                                                                                          |
| ---------------------- | -------------------------- | --------------------------------------------------------------------------------------------------- |
| `constructor(options)` | `GeneratorOptions`，见下表 | 同步读取 tsconfig，读不到抛出类别为 `configuration` 的 `GeneratorError`；唯一的参数是选项            |
| `generate()`           | 无参数                     | `Promise<GenerationResult>`；先读配置再读文档、解析聚合、写模型/客户端/index、格式化并保存其拥有的输出 |
| `DEFAULT_CONFIG_PATH`  | 常量                       | `./wow-generator.config.json`                                                                       |

<span id="generatoroptions"></span>

| `GeneratorOptions` 字段 | 默认值                         | 含义                                                         |
| ----------------------- | ------------------------------ | ------------------------------------------------------------ |
| `inputPath`             | 必填                           | OpenAPI 3.x 文档的路径或 HTTP/HTTPS URL                      |
| `outputDir`             | 必填                           | 生成文件的写入目录                                           |
| `configPath`            | 存在时为 `DEFAULT_CONFIG_PATH` | [配置](./configuration)的路径或 URL；显式指定的必须存在      |
| `tsConfigFilePath`      | 无                             | 项目的 tsconfig，用于解析输出所导入的模块                    |
| `logger`                | `new ConsoleLogger()`          | 接收进度、警告和错误                                         |
| `headers`               | 无                             | `inputPath` 或 `configPath` 为 HTTP/HTTPS URL 时发送的请求头 |
| `timeoutMs`             | `30000`                        | 获取 HTTP/HTTPS 文档的超时毫秒数                             |
| `schemaDocs`            | `'summary'`                    | `'full'` 时在每个模型的文档注释中嵌入其 JSON schema          |

`GeneratorOptions` 不再继承 ts-morph 的 `ProjectOptions`；传给项目的只有 `tsConfigFilePath`。

<span id="generationresult"></span>

`generate()` 返回 `GenerationResult`：`files` 为写出文件的绝对路径（已排序）；`configPath` 为读取的配置，没有找到配置时为 `undefined`；`warnings` 为本次运行记录的警告数。文档、配置或输出目录读不到、理解不了或写不进时以 [`GeneratorError`](#generatorerror) 拒绝，只有生成器自身的缺陷才以普通 `Error` 拒绝。

内部 ts-morph Project 以及生成 index、格式化这些步骤都是 private，包的公开声明不引用 ts-morph。没有 close/dispose、取消信号或 watch 方法。同一实例/输出目录不要并发调用 generate()，没有锁定契约。

## 日志器

<span id="logger"></span>

`Logger` 实现四个方法，签名都是 `(message: string, ...params: unknown[]) => void`：`debug` 记录每个步骤和细节，`info` 记录本次运行的结果，`warn` 记录运行继续了、但多半不是用户本意的情况，`error` 记录失败。生成器统计 `warn` 的调用次数，作为 `GenerationResult.warnings`。

<span id="consolelogger"></span>

`ConsoleLogger` 是 CLI 使用的日志器。`ConsoleLoggerOptions.level` 是 `LogLevel`：`quiet` 只输出 `warn` 和 `error`，`normal`（默认）还输出 `info`，`verbose` 还输出每条 `debug`，并且每行带时间戳。`decorate` 控制是否带符号，默认在 TTY 且未设置 `NO_COLOR` 时开启。`SilentLogger` 不输出任何内容。

## 错误

<span id="generatorerror"></span>

`GeneratorError` 带有 `kind`（`GeneratorErrorKind`：`input`、`configuration`、`specification` 或 `output`）和 `exitCode`（该类别对应的 CLI 退出码）。`EXIT_CODES` 为 `success` 0、`internal` 1、`input` 2、`configuration` 3、`specification` 4、`output` 5、`interrupted` 130，见 [CLI 退出码](./cli#失败与退出码)。

## 完整脚本

```ts
import {
  CodeGenerator,
  ConsoleLogger,
  GeneratorError,
} from '@ahoo-wang/wow-generator';

const generator = new CodeGenerator({
  inputPath: './openapi.json',
  outputDir: './src/generated',
  tsConfigFilePath: './tsconfig.json',
  logger: new ConsoleLogger({ level: 'quiet' }),
});
try {
  const { files, warnings } = await generator.generate();
  console.log(`Generated ${files.length} files, ${warnings} warnings`);
} catch (error) {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = error instanceof GeneratorError ? error.exitCode : 1;
}
```

<span id="default_config_path"></span>

**`DEFAULT_CONFIG_PATH`** — [typescript/wow-generator/src/api/configuration.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/api/configuration.ts)

<span id="codegenerator-api"></span>

**`CodeGenerator`** — [typescript/wow-generator/src/pipeline/codeGenerator.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/pipeline/codeGenerator.ts)

**`ConsoleLogger`、`SilentLogger`** — [typescript/wow-generator/src/api/logger.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/api/logger.ts)

**`GeneratorError`、`EXIT_CODES`** — [typescript/wow-generator/src/api/errors.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/api/errors.ts)

**`GeneratorOptions`、`GenerationResult`** — [typescript/wow-generator/src/api/options.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/api/options.ts)

**`GeneratorConfiguration`、`ApiClientConfiguration`** — [typescript/wow-generator/src/api/configuration.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/api/configuration.ts)

**`Logger`、`LogLevel`、`ConsoleLoggerOptions`** — [typescript/wow-generator/src/api/logger.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/api/logger.ts)
