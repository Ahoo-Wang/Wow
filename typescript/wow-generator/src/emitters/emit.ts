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

import type { GenerationModel } from '../analysis/model';
import { emitApiClient } from './apiClients';
import { emitModel } from './models';
import type { EmitTarget } from './target';

/**
 * Describes every file of a generation to its module: the models, then the
 * API clients. Nothing reaches a source file until the modules are built.
 *
 * The order is the one the files have always been written in, so the
 * imports of a module, and the aliases a name clash gives them, come out
 * the same.
 *
 * @param model - What the document generates
 * @param target - Where it is written
 */
export function emitGeneration(
  model: GenerationModel,
  target: EmitTarget,
): void {
  model.models.forEach(declaration => emitModel(declaration, target));
  model.apiClients.forEach(client => emitApiClient(client, target));
}
