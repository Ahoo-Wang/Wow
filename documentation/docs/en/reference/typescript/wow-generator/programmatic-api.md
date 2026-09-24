---
title: 'Programmatic API'
description: 'Programmatic API — @ahoo-wang/wow-generator'
---

# Programmatic API

The package root exports exactly `CodeGenerator` and `DEFAULT_CONFIG_PATH`. The executable is separately exposed as the `wow-generator` binary. Do not import internal AggregateResolver, ModelGenerator, GenerateContext, ConsoleLogger, setupCLI, parser helpers or internal option types from the package root or undocumented subpaths.

## CodeGenerator

| Member                           | Parameters/defaults                                                                                                             | Return and effects                                                                                                         |
| -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| `constructor(options)`           | Required inputPath:string, outputDir:string, logger; optional configPath and ts-morph ProjectOptions including tsConfigFilePath | Creates an internal Project synchronously; invalid project options may throw                                               |
| `generate()`                     | No arguments                                                                                                                    | Promise&lt;void&gt;; loads spec/config, resolves aggregates, writes models/clients/indexes, formats and saves owned output |
| `generateIndex(directory)`       | Required ts-morph Directory                                                                                                     | void; recursively creates/replaces index.ts exports in the internal project; does not save by itself                       |
| `optimizeSourceFiles(directory)` | Required Directory                                                                                                              | void; formats, organizes/fixes imports; during generation filters to recorded generated files; does not save               |
| `DEFAULT_CONFIG_PATH`            | Constant                                                                                                                        | `./fetcher-generator.config.json`                                                                                          |

The internal Project is private. The last two methods are public orchestration helpers with a Directory argument, not a separate persistence API. Normal applications should call generate(). There is no close/dispose, cancellation signal, watch method, or structured diagnostics return. Avoid concurrent generate() calls on the same instance/output directory; there is no locking contract.

## Complete script

The logger must implement all five methods. `ConstructorParameters` obtains the supported option shape without importing an unexported name.

```ts
import { CodeGenerator } from '@ahoo-wang/wow-generator';

type Options = ConstructorParameters<typeof CodeGenerator>[0];
const logger: Options['logger'] = {
  info: console.log,
  success: console.log,
  error: console.error,
  progress: (message, level = 0) => console.log(level, message),
  progressWithCount: (current, total, message) =>
    console.log(current, total, message),
};
const generator = new CodeGenerator({
  inputPath: './openapi.json',
  outputDir: './src/generated',
  tsConfigFilePath: './tsconfig.json',
  logger,
});
try {
  await generator.generate();
} catch (error) {
  console.error('Generation failed', error);
  process.exitCode = 1;
}
```

<span id="default_config_path"></span>

**`DEFAULT_CONFIG_PATH`** — [typescript/wow-generator/src/index.ts:35](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/index.ts#L35)

<span id="codegenerator-api"></span>

**`CodeGenerator`** — [typescript/wow-generator/src/index.ts:53](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/index.ts#L53)
