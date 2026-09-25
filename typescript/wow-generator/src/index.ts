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

export type {
  ApiClientConfiguration,
  GeneratorConfiguration,
} from './api/configuration';
export { DEFAULT_CONFIG_PATH } from './api/configuration';
export type { GeneratorErrorKind } from './api/errors';
export { EXIT_CODES, GeneratorError } from './api/errors';
export type { ConsoleLoggerOptions, LogLevel, Logger } from './api/logger';
export { ConsoleLogger, SilentLogger } from './api/logger';
export type { GenerationResult, GeneratorOptions } from './api/options';
export { CodeGenerator } from './pipeline/codeGenerator';
