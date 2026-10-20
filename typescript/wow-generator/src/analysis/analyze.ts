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

import type { GeneratorConfiguration } from '../api/configuration';
import type { OpenApiDocument } from '../openapi/document';
import { analyzeApiClients } from './apiClients';
import type { Analysis } from './model';
import { analyzeModels } from './models';

/**
 * Decides what an OpenAPI document generates: its models and API clients. A
 * pure function of the document and the configuration; nothing is written
 * and nothing logged.
 *
 * @param document - The document, left unchanged
 * @param config - The generator configuration
 * @returns The generation model, and the warnings for what it leaves out
 * @throws GeneratorError when the document asks for code that cannot
 * compile: two schemas of one model, two operations of one method
 */
export function analyze(
  document: OpenApiDocument,
  config: GeneratorConfiguration,
): Analysis {
  const warnings: string[] = [];
  const models = analyzeModels(document);
  const apiClients = analyzeApiClients(document, config, warnings);
  return { model: { models, apiClients }, warnings };
}
