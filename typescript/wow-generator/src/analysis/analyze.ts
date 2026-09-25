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
import type { WowModel } from '../wow/model';
import { analyzeAggregates, assertCommandTypeNamesFree } from './aggregates';
import { analyzeApiClients } from './apiClients';
import type { Analysis } from './model';
import { analyzeContexts, analyzeModels } from './models';

/**
 * Decides what a document generates: its bounded contexts, models, command
 * and query clients and API clients. A pure function of the document, its
 * Wow model and the configuration; nothing is written and nothing logged.
 *
 * @param document - The document, left unchanged
 * @param wow - Its Wow model
 * @param config - The generator configuration
 * @returns The generation model, and the warnings for what it leaves out
 * @throws GeneratorError when the document asks for code that cannot
 * compile: two schemas of one model, a command type named like a model,
 * two operations of one method
 */
export function analyze(
  document: OpenApiDocument,
  wow: WowModel,
  config: GeneratorConfiguration,
): Analysis {
  const warnings: string[] = [];
  const contexts = analyzeContexts(wow);
  const models = analyzeModels(document, wow);
  const aggregates = analyzeAggregates(document, wow);
  assertCommandTypeNamesFree(aggregates, models);
  const apiClients = analyzeApiClients(document, wow, config, warnings);
  return { model: { contexts, models, aggregates, apiClients }, warnings };
}
