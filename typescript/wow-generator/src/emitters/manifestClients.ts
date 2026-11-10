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

import type { ModuleSet } from '../emit/moduleBuilder';
import type { ManifestModel } from '../manifest/model';
import { emitAggregate } from './manifest/aggregate';
import { emitCommandClient } from './manifest/commandClient';
import { emitContextClient } from './manifest/contextClient';
import { emitContract } from './manifest/contract';
import { type TypeFiles, WOW_CLIENT } from './manifest/generatedModule';
import { emitQueryClient } from './manifest/queryClient';
import { emitRouting } from './manifest/routing';
import { emitServiceRoutes } from './manifest/serviceRoutes';
import { emitTextNumbers } from './manifest/textNumbers';
import { emitType } from './manifest/typeDeclarations';

/**
 * Describes every file a Manifest generates to its module: the types of each
 * bounded context, its error codes and wait
 * helpers, and the aggregate, command and query clients of each aggregate,
 * all on `@ahoo-wang/wow-client`.
 *
 * @param model - What the Manifest generates
 * @param modules - The modules of the run
 * @param outputDir - The output directory
 */
export function emitManifestGeneration(
  model: ManifestModel,
  modules: ModuleSet,
  outputDir: string,
): void {
  const files: TypeFiles = new Map(
    model.types.map(type => [type.name, type.file]),
  );
  for (const type of model.types) {
    emitType(modules.module(type.file), type, files, outputDir);
  }
  for (const context of model.contexts) {
    emitContract(context, modules, outputDir);
    for (const aggregate of context.aggregates) {
      emitAggregate(aggregate, context, modules, files, outputDir);
      emitCommandClient(aggregate, context, modules, files, outputDir);
      emitQueryClient(aggregate, modules, files, outputDir);
    }
    emitRouting(context, modules);
    emitTextNumbers(context, modules, outputDir, WOW_CLIENT);
    emitServiceRoutes(context, modules, files, outputDir);
    emitContextClient(context, modules, outputDir);
  }
}
