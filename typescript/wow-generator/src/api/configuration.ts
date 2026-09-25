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
 * Where the generator looks for its configuration when none is named.
 */
export const DEFAULT_CONFIG_PATH = './wow-generator.config.json';

/**
 * The generator configuration, read from {@link DEFAULT_CONFIG_PATH} or the
 * path `GeneratorOptions.configPath` names.
 */
export interface GeneratorConfiguration {
  /**
   * tag name -> api client configuration
   */
  apiClients?: Record<string, ApiClientConfiguration>;
}

export interface ApiClientConfiguration {
  /**
   * The path parameters the client leaves out, because an interceptor fills
   * them.
   *
   * Default: `['tenantId', 'ownerId']` for a Wow document (one with
   * `x-wow-context-alias` or aggregates), whose CoSec interceptor fills them;
   * none for any other document.
   */
  ignorePathParameters?: string[];
  /**
   * Method names by operationId, overriding the name derived from it. Use it
   * when two operations of one tag derive the same name.
   */
  methodNames?: Record<string, string>;
}
