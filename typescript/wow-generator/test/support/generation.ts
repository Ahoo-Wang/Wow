/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { execFileSync } from 'node:child_process';
import {
  mkdtempSync,
  readdirSync,
  readFileSync,
  realpathSync,
  rmSync,
  statSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative, resolve } from 'node:path';
import ts from 'typescript';
import { expect } from 'vitest';
import type { Project } from 'ts-morph';
import type { Logger } from '../../src/api/logger';
import type { GeneratorOptions } from '../../src/api/options';
import { CodeGenerator } from '../../src/pipeline/codeGenerator';
import type { SeamOptions } from '../../src/pipeline/projectSeam';
import { PROJECT_SEAM } from '../../src/pipeline/projectSeam';
import { runGenerate } from '../../src/cli/runGenerate';

/** The package root, whose node_modules resolve `@ahoo-wang/*`. */
export const PACKAGE_ROOT = resolve(__dirname, '..', '..');

/**
 * A directory outside the package, where nothing resolves `@ahoo-wang/*`
 * until {@link linkNodeModules} links it: generating there proves the output
 * does not depend on what the output directory happens to resolve.
 */
export function coldDirectory(prefix: string, cleanup: string[]): string {
  const dir = mkdtempSync(join(tmpdir(), `wow-generator-${prefix}-`));
  cleanup.push(dir);
  return dir;
}

export function removeDirectories(directories: string[]): void {
  directories
    .splice(0)
    .forEach(dir => rmSync(dir, { recursive: true, force: true }));
}

/** Links the package's node_modules into a directory, so its imports resolve. */
export function linkNodeModules(dir: string): void {
  symlinkSync(
    join(PACKAGE_ROOT, 'node_modules'),
    join(dir, 'node_modules'),
    'junction',
  );
}

/**
 * A generator that writes into the given ts-morph project, such as an
 * in-memory one, rather than one it reads from `tsConfigFilePath`.
 */
export function createCodeGenerator(
  options: GeneratorOptions,
  project: Project,
): CodeGenerator {
  const seamOptions: SeamOptions = { ...options, [PROJECT_SEAM]: project };
  return new CodeGenerator(seamOptions);
}

/** A logger that records warnings and drops everything else. */
export function recordingLogger(): Logger & { warnings: string[] } {
  const warnings: string[] = [];
  return {
    warnings,
    debug() {},
    info() {},
    warn(message: string) {
      warnings.push(message);
    },
    error() {},
  };
}

/**
 * Runs the CLI's generate command on an OpenAPI document written to a cold
 * directory.
 *
 * @returns The exit code, the output directory and the logger
 */
export async function generateCold(
  spec: unknown,
  cleanup: string[],
  config?: unknown,
) {
  const dir = coldDirectory('probe', cleanup);
  const input = join(dir, 'openapi.json');
  writeFileSync(input, JSON.stringify(spec));
  let configPath: string | undefined;
  if (config !== undefined) {
    configPath = join(dir, 'wow-generator.config.json');
    writeFileSync(configPath, JSON.stringify(config));
  }
  const output = join(dir, 'out');
  const logger = recordingLogger();
  const errors: string[] = [];
  logger.error = (message: string) => {
    errors.push(message);
  };
  const exitCode = await runGenerate(
    { input, output, config: configPath },
    logger,
  );
  return { exitCode, dir, output, logger, errors };
}

/**
 * Generates a document into a directory outside the package, where
 * `@ahoo-wang/*` does not resolve: the output must not depend on what its
 * directory resolves. The project's tsconfig sits beside the output, as in an
 * application, and includes only the output.
 *
 * @param name - Names the directory, for someone reading a leftover one
 * @param options - The document and the configuration, relative to the
 * package root
 * @param cleanup - Collects the directory, for {@link removeDirectories}
 * @returns The output directory, and the warnings the run logged, one a line,
 * with the output directory written as `<output>` so they read the same on
 * every machine
 */
export async function generateProject(
  name: string,
  options: { input: string; config?: string },
  cleanup: string[],
): Promise<{ output: string; warnings: string }> {
  const dir = coldDirectory(`e2e-${name}`, cleanup);
  const tsConfigFilePath = join(dir, 'tsconfig.json');
  writeFileSync(
    tsConfigFilePath,
    JSON.stringify({
      compilerOptions: {
        target: 'ES2022',
        module: 'ESNext',
        moduleResolution: 'bundler',
        strict: true,
        experimentalDecorators: true,
        skipLibCheck: true,
        noEmit: true,
      },
      include: ['src/**/*'],
    }),
  );
  const output = join(dir, 'src', 'generated');
  const logger = recordingLogger();
  const exitCode = await runGenerate(
    {
      input: join(PACKAGE_ROOT, options.input),
      output,
      config: options.config && join(PACKAGE_ROOT, options.config),
      tsConfigFilePath,
    },
    logger,
  );
  expect(exitCode, `Generating ${options.input}`).toBe(0);
  // The real path first: on macOS /private/var/… ends with /var/….
  const warnings = [join(realpathSync(dir), 'src', 'generated'), output].reduce(
    (text, path) => text.split(path).join('<output>'),
    [...logger.warnings, ''].join('\n'),
  );
  return { output, warnings };
}

function listTypeScriptFiles(dir: string): string[] {
  return readdirSync(dir).flatMap(name => {
    const path = join(dir, name);
    if (name === 'node_modules') return [];
    if (statSync(path).isDirectory()) return listTypeScriptFiles(path);
    return path.endsWith('.ts') ? [path] : [];
  });
}

const COMMON_OPTIONS: ts.CompilerOptions = {
  target: ts.ScriptTarget.ES2022,
  lib: ['lib.es2022.d.ts', 'lib.dom.d.ts', 'lib.dom.iterable.d.ts'],
  strict: true,
  noEmit: true,
  skipLibCheck: true,
  experimentalDecorators: true,
  emitDecoratorMetadata: true,
  types: [],
};

export const BUNDLER_OPTIONS: ts.CompilerOptions = {
  ...COMMON_OPTIONS,
  module: ts.ModuleKind.ESNext,
  moduleResolution: ts.ModuleResolutionKind.Bundler,
};

export const NODE_NEXT_OPTIONS: ts.CompilerOptions = {
  ...COMMON_OPTIONS,
  module: ts.ModuleKind.NodeNext,
  moduleResolution: ts.ModuleResolutionKind.NodeNext,
};

/**
 * Runs generated code: transpiles every `.ts` file of a directory beside
 * itself, then runs an ES module script there with Node. Requests go to a
 * stub `fetch`, which answers `{}` and records each request.
 *
 * @param dir - A directory holding the generated files
 * @param script - The body of an ES module; `requests` holds what it sent
 * and `Fetcher` is imported
 * @returns What the script printed
 */
export function runGenerated(dir: string, script: string): string {
  for (const file of listTypeScriptFiles(dir)) {
    const { outputText } = ts.transpileModule(readFileSync(file, 'utf8'), {
      compilerOptions: {
        target: ts.ScriptTarget.ES2022,
        module: ts.ModuleKind.ESNext,
        experimentalDecorators: true,
      },
    });
    writeFileSync(file.replace(/\.ts$/, '.js'), outputText);
  }
  writeFileSync(join(dir, 'package.json'), '{ "type": "module" }\n');
  try {
    statSync(join(dir, 'node_modules'));
  } catch {
    linkNodeModules(dir);
  }
  const path = join(dir, 'run.mjs');
  writeFileSync(
    path,
    `import { Fetcher } from '@ahoo-wang/fetcher';
const requests = [];
globalThis.fetch = async (url, init) => {
  requests.push({
    url: String(url),
    method: init?.method,
    headers: Object.fromEntries(new Headers(init?.headers).entries()),
    body: init?.body ?? null,
  });
  return new Response('{}', { headers: { 'Content-Type': 'application/json' } });
};
${script}
`,
  );
  return execFileSync(process.execPath, [path], {
    cwd: dir,
    encoding: 'utf8',
    timeout: 20000,
  });
}

/**
 * Type-checks generated code as a project using it would, with `tsc
 * --strict`.
 *
 * The directory gets the package's node_modules linked in, and a
 * `package.json` of an ES module package, which `NodeNext` needs to treat
 * `.ts` files as ES modules.
 *
 * @param dir - A directory holding the generated files, and possibly extra
 * files that use them
 * @param options - The compiler options
 * @returns Each diagnostic as `file:line TScode message`
 */
export function typeCheck(dir: string, options: ts.CompilerOptions): string[] {
  const files = listTypeScriptFiles(dir);
  writeFileSync(join(dir, 'package.json'), '{ "type": "module" }\n');
  try {
    statSync(join(dir, 'node_modules'));
  } catch {
    linkNodeModules(dir);
  }
  const program = ts.createProgram(files, options);
  return ts.getPreEmitDiagnostics(program).map(diagnostic => {
    const message = ts.flattenDiagnosticMessageText(
      diagnostic.messageText,
      ' ',
    );
    if (!diagnostic.file || diagnostic.start === undefined) {
      return `TS${diagnostic.code} ${message}`;
    }
    const { line } = diagnostic.file.getLineAndCharacterOfPosition(
      diagnostic.start,
    );
    return `${relative(dir, diagnostic.file.fileName)}:${line + 1} TS${diagnostic.code} ${message}`;
  });
}
