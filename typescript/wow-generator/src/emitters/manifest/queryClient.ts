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

import type { ModuleSet } from '../../emit/moduleBuilder';
import type { AggregateModel } from '../../manifest/model';
import {
  addStatement,
  doc,
  importTypes,
  type TypeFiles,
  WOW_CLIENT,
} from './generatedModule';

export function emitQueryClient(
  aggregate: AggregateModel,
  modules: ModuleSet,
  files: TypeFiles,
  outputDir: string,
): void {
  const module = modules.module(`${aggregate.directory}/queryClient.ts`);
  const name = aggregate.typeName;
  const state = aggregate.stateType;
  const owned = aggregate.idSource === 'owner';
  const stateClient = owned ? 'OwnStateClient' : 'StateClient';
  const client = [stateClient, 'WowClientOptions'];
  const aggregateNames = [`${name}Access`, `${name}Aggregate`, `${name}Event`];
  const constructor = `  /** @param options - How the service is reached */\n  constructor(options?: WowClientOptions) {\n    super(${name}Aggregate, options);\n  }`;
  if (aggregate.queryModels.includes('snapshot')) {
    client.push('SnapshotQueryClient');
    aggregateNames.push(`${name}StateField`);
    addStatement(
      module,
      `${doc([`Queries the snapshots of \`${aggregate.context}.${aggregate.name}\`: \`POST /${aggregate.context}/${aggregate.name}/snapshot/query\`.`])}export class ${name}SnapshotQueryClient extends SnapshotQueryClient<${state}, ${name}StateField, ${name}Access> {\n${constructor}\n}`,
    );
  }
  if (aggregate.queryModels.includes('events')) {
    client.push('EventQueryClient');
    addStatement(
      module,
      `${doc([`Queries the event streams of \`${aggregate.context}.${aggregate.name}\`, one record per commit: \`POST /${aggregate.context}/${aggregate.name}/events/query\`.`])}export class ${name}EventQueryClient extends EventQueryClient<${name}Event, ${name}Access> {\n${constructor}\n}`,
    );
  }
  addStatement(
    module,
    `${doc([
      owned
        ? `Reads the states of \`${aggregate.context}.${aggregate.name}\`, at a version or a time, and their traces: the caller's own by no id (\`readOwn\`, \`getOwn\`, \`findOwn\`, \`traceOwn\`: \`GET /${aggregate.context}/${aggregate.name}/state\`), and by id for a caller with no owner (\`GET /${aggregate.context}/${aggregate.name}/{id}/state\`).`
        : `Reads the states of \`${aggregate.context}.${aggregate.name}\`, at a version or a time, and their traces: \`GET /${aggregate.context}/${aggregate.name}/{id}/state\`.`,
    ])}export class ${name}StateClient extends ${stateClient}<${state}, ${name}Event, ${name}Access> {\n${constructor}\n}`,
  );
  module.imports.add(WOW_CLIENT, client.sort());
  module.imports.add('./aggregate.js', aggregateNames);
  importTypes(module, outputDir, files, [state]);
}
