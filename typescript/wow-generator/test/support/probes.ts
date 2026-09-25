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

/**
 * Probes: each feeds the smallest document that shows a behaviour through
 * the CLI, in a directory where nothing resolves `@ahoo-wang/*`, then
 * compiles the output with `tsc --strict`. A problem either becomes code that
 * compiles and means what the document says, or a clear error with a
 * non-zero exit code.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterAll, expect } from 'vitest';
import { EXIT_CODES } from '../../src/api/errors';
import {
  BUNDLER_OPTIONS,
  generateCold,
  removeDirectories,
  typeCheck,
} from './generation';

const directories: string[] = [];
afterAll(() => removeDirectories(directories));

/** A document generated and compiled, and a reader of its output. */
export type CompilingGeneration = Awaited<ReturnType<typeof generateCompiling>>;

/** Generates a document and expects it to compile, optionally with a consumer file. */
export async function generateCompiling(
  spec: unknown,
  consumer?: string,
  config?: unknown,
) {
  const result = await generateCold(spec, directories, config);
  expect(result.errors).toEqual([]);
  expect(result.exitCode).toBe(EXIT_CODES.success);
  if (consumer !== undefined) {
    writeFileSync(join(result.dir, 'consumer.ts'), consumer);
  }
  expect(typeCheck(result.dir, BUNDLER_OPTIONS)).toEqual([]);
  return {
    ...result,
    read: (path: string) => readFileSync(join(result.output, path), 'utf8'),
  };
}

/** Generates a document the CLI refuses, and returns its one error line. */
export async function generateFailing(spec: unknown, config?: unknown) {
  const result = await generateCold(spec, directories, config);
  expect(result.exitCode).toBe(EXIT_CODES.specification);
  expect(result.errors).toHaveLength(1);
  return result.errors[0];
}

/**
 * A generation several probes of a file read, made by the first one that
 * asks: the same document need not be generated and compiled again for each.
 * A probe that changes the output generates its own.
 */
export function sharedGeneration(
  generate: () => Promise<CompilingGeneration>,
): () => Promise<CompilingGeneration> {
  let generation: Promise<CompilingGeneration> | undefined;
  return () => (generation ??= generate());
}
