---
title: 'Programmatic API'
description: 'Programmatic API — @ahoo-wang/wow-generator'
---

# Programmatic API

The package root exports `CodeGenerator`, `DEFAULT_CONFIG_PATH`, the loggers `ConsoleLogger` and `SilentLogger`, `GeneratorError` and `EXIT_CODES`, and the types `GeneratorOptions`, `GenerationResult`, `GeneratorConfiguration`, `ApiClientConfiguration`, `Logger`, `ConsoleLoggerOptions`, `LogLevel` and `GeneratorErrorKind`. The executable is separately exposed as the `wow-generator` binary. Do not import internal AggregateResolver, ModelGenerator, GenerateContext, setupCLI or parser helpers from undocumented subpaths. Both ESM `import` and CommonJS `require` work.

## CodeGenerator

| Member                 | Parameters/defaults           | Return and effects                                                                                                                 |
| ---------------------- | ----------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| `constructor(options)` | `GeneratorOptions`, see below | Creates an internal ts-morph Project synchronously; an unreadable tsconfig throws                                                  |
| `generate()`           | No arguments                  | `Promise<GenerationResult>`; loads spec/config, resolves aggregates, writes models/clients/indexes, formats and saves owned output |
| `DEFAULT_CONFIG_PATH`  | Constant                      | `./wow-generator.config.json`                                                                                                      |

<span id="generatoroptions"></span>

| `GeneratorOptions` field | Default                          | Meaning                                                                        |
| ------------------------ | -------------------------------- | ------------------------------------------------------------------------------ |
| `inputPath`              | Required                         | Path or HTTP/HTTPS URL of the OpenAPI 3.x document                             |
| `outputDir`              | Required                         | Directory the generated files are written to                                   |
| `configPath`             | `DEFAULT_CONFIG_PATH` if present | Path or URL of the [configuration](./configuration); a named one has to exist  |
| `tsConfigFilePath`       | None                             | The project's tsconfig, used to resolve the modules the output imports         |
| `logger`                 | `new ConsoleLogger()`            | Receives progress, warnings and errors                                         |
| `headers`                | None                             | Request headers used when `inputPath` or `configPath` is an HTTP/HTTPS URL     |
| `timeoutMs`              | `30000`                          | Milliseconds before fetching an HTTP/HTTPS document is abandoned               |

`GeneratorOptions` no longer extends ts-morph's `ProjectOptions`; only `tsConfigFilePath` is passed to the project.

<span id="generationresult"></span>

`generate()` resolves to a `GenerationResult`: `files`, the sorted absolute paths written; `configPath`, the configuration read, or `undefined` when none was found; and `warnings`, how many warnings the run logged. It rejects with a [`GeneratorError`](#generatorerror) when the document or the configuration cannot be read or understood, and with a plain `Error` for anything unexpected, such as a failed write.

The internal Project and the index and formatting steps are private. There is no close/dispose, cancellation signal or watch method. Avoid concurrent generate() calls on the same instance/output directory; there is no locking contract.

## Loggers

<span id="logger"></span>

A `Logger` implements `info`, `success`, `error`, `progress` and `progressWithCount`; `warn` is optional and falls back to `info`. The generator counts the warnings it passes to the logger for `GenerationResult.warnings`.

<span id="consolelogger"></span>

`ConsoleLogger` is the CLI's logger. `ConsoleLoggerOptions.level` is a `LogLevel`: `quiet` prints warnings and errors, `normal` (the default) also success lines, `verbose` also every `info`/`progress` line with a timestamp. `decorate` prefixes lines with symbols; it defaults to true on a TTY unless `NO_COLOR` is set. `SilentLogger` prints nothing.

## Errors

<span id="generatorerror"></span>

`GeneratorError` carries `kind`, a `GeneratorErrorKind` (`input`, `configuration` or `specification`), and `exitCode`, the CLI exit code for that kind. `EXIT_CODES` maps `success` 0, `internal` 1, `input` 2, `configuration` 3, `specification` 4 and `interrupted` 130; see [CLI exit codes](./cli#failures-and-exit-codes).

## Complete script

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

**`DEFAULT_CONFIG_PATH`** — [typescript/wow-generator/src/utils/configuration.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/utils/configuration.ts)

<span id="codegenerator-api"></span>

**`CodeGenerator`** — [typescript/wow-generator/src/index.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/index.ts)

**`ConsoleLogger`, `SilentLogger`** — [typescript/wow-generator/src/utils/logger.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/utils/logger.ts)

**`GeneratorError`, `EXIT_CODES`** — [typescript/wow-generator/src/errors.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/errors.ts)

**`GeneratorOptions`, `GenerationResult`, `GeneratorConfiguration`, `ApiClientConfiguration`, `Logger`** — [typescript/wow-generator/src/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/types.ts)
