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

import type { OpenAPI } from '@ahoo-wang/fetcher-openapi';
import type { Project } from 'ts-morph';
import type { BoundedContextAggregates } from './aggregate';

/**
 * Options of a {@link CodeGenerator} run.
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

/**
 * Logger interface for friendly logging during code generation.
 */
export interface Logger {
  /** Log informational messages */
  info(message: string, ...params: any[]): void;

  /**
   * Log warnings - something the generator carried on past, but that the user
   * probably did not intend.
   *
   * Optional so that an existing Logger keeps compiling; callers reach it
   * through {@link warn}, which falls back to {@link info}. The rest
   * parameter only passes values through to the sink, so it takes `unknown`
   * rather than the `any` the older methods were written with.
   */
  warn?(message: string, ...params: unknown[]): void;

  /** Log success messages */
  success(message: string, ...params: any[]): void;

  /** Log error messages */
  error(message: string, ...params: any[]): void;

  /** Log progress messages */
  progress(message: string, level?: number, ...params: any[]): void;

  /** Log progress messages with count */
  progressWithCount(
    current: number,
    total: number,
    message: string,
    level?: number,
    ...params: any[]
  ): void;
}

/**
 * Context object containing all necessary data for code generation.
 */
export interface GenerateContextInit {
  /** The parsed OpenAPI specification */
  openAPI: OpenAPI;
  /** The ts-morph project instance */
  project: Project;
  /** Output directory for generated files */
  outputDir: string;
  contextAggregates: BoundedContextAggregates;
  /** Optional logger for friendly output */
  logger: Logger;
  config?: GeneratorConfiguration;
}

export interface GeneratorConfiguration {
  /**
   * tag name -> api client configuration
   */
  apiClients?: Record<string, ApiClientConfiguration>;
}

export interface ApiClientConfiguration {
  /**
   * The path parameters to ignore
   *
   * default: ['tenantId','ownerId']
   */
  ignorePathParameters?: string[];
}
