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

import { OpenAPI } from '@ahoo-wang/fetcher-openapi';
import { Project } from 'ts-morph';
import { BoundedContextAggregates } from './aggregate';

/**
 * Configuration options for the code generator.
 */
export interface GeneratorOptions {
  /** The ts-morph project instance to use for code generation */
  readonly project: Project;
  /** Path to the input OpenAPI specification file */
  readonly inputPath: string;
  /** Output directory for generated files */
  readonly outputDir: string;
  /** Optional logger for friendly output */
  readonly logger: Logger;
}

/**
 * Logger interface for friendly logging during code generation.
 */
export interface Logger {
  /** Log informational messages */
  info(message: string, ...params: any[]): void;

  /** Log success messages */
  success(message: string, ...params: any[]): void;

  /** Log error messages */
  error(message: string, ...params: any[]): void;

  /** Log progress messages */
  progress(message: string, ...params: any[]): void;
}

/**
 * Context object containing all necessary data for code generation.
 */
export interface GenerateContext {
  /** The parsed OpenAPI specification */
  openAPI: OpenAPI;
  /** The ts-morph project instance */
  project: Project;
  /** Output directory for generated files */
  outputDir: string;
  contextAggregates: BoundedContextAggregates;
  /** Optional logger for friendly output */
  logger: Logger;
}
