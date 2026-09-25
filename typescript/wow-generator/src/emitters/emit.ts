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

import type { BoundedContextModel, GenerationModel } from '../analysis/model';
import { quoteStringLiteral } from '../naming/naming';
import { emitApiClient } from './apiClients';
import { emitCommandClient } from './commandClients';
import { emitModel } from './models';
import { emitQueryClient } from './queryClients';
import type { EmitTarget } from './target';

/**
 * Describes every file of a generation to its module: the bounded contexts,
 * the models, the query and command clients of each aggregate, then the API
 * clients. Nothing reaches a source file until the modules are built.
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
  model.contexts.forEach(context => emitBoundedContext(context, target));
  model.models.forEach(declaration => emitModel(declaration, target));
  model.aggregates.forEach(aggregate => emitQueryClient(aggregate, target));
  model.aggregates.forEach(aggregate => emitCommandClient(aggregate, target));
  model.apiClients.forEach(client => emitApiClient(client, target));
}

/** Writes a bounded context's alias constant into its own file. */
function emitBoundedContext(
  context: BoundedContextModel,
  target: EmitTarget,
): void {
  target.modules
    .module(context.file)
    .add(
      `export const ${context.constantName} = ${quoteStringLiteral(context.alias)};`,
    );
}
