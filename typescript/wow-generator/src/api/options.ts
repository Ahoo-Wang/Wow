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

import type { Logger } from './logger';

/**
 * How much of each schema a generated model's doc comment carries.
 *
 * - `summary`: title, description, schema key, format, default, example and
 *   constraints.
 * - `full`: the summary plus the complete JSON schema.
 */
export type SchemaDocs = 'summary' | 'full';

/**
 * Options of a `CodeGenerator` run.
 */
export interface GeneratorOptions {
  /** Path or http(s) URL of the OpenAPI 3 document. */
  readonly inputPath: string;
  /** Directory the generated files are written to. */
  readonly outputDir: string;
  /**
   * Path or URL of the generator configuration. When omitted, the generator
   * reads `./wow-generator.config.json` if it exists.
   */
  readonly configPath?: string;
  /**
   * The project's `tsconfig.json`. Its compiler options decide how the
   * generator resolves the modules it imports while it tidies the output.
   */
  readonly tsConfigFilePath?: string;
  /** Receives progress, warnings and errors. Defaults to a `ConsoleLogger` at the `normal` level. */
  readonly logger?: Logger;
  /** Request headers used when `inputPath` or `configPath` is an http(s) URL. */
  readonly headers?: Record<string, string>;
  /** Milliseconds before fetching an http(s) document is abandoned. Defaults to 30000. */
  readonly timeoutMs?: number;
  /** How much of each schema the model doc comments carry. Defaults to `summary`. */
  readonly schemaDocs?: SchemaDocs;
}

/**
 * What a generation produced.
 */
export interface GenerationResult {
  /** Absolute paths of the files written, sorted. */
  readonly files: readonly string[];
  /** The configuration read, or undefined when none was found. */
  readonly configPath?: string;
  /** How many warnings the run logged. */
  readonly warnings: number;
}
