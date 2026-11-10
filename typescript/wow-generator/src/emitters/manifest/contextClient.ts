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

import { join } from 'node:path';
import { relativeModuleSpecifier } from '../../emit/imports';
import type { ModuleSet } from '../../emit/moduleBuilder';
import type { ContextModel } from '../../manifest/model';
import { camelCase, resolvePropertyName } from '../../naming/naming';
import { addStatement, doc, WOW_CLIENT } from './generatedModule';

/**
 * The clients of a context in one place: per aggregate its command client and
 * its query and state clients, on the same options. Each client stays its own
 * module too, so an application that imports a single client loads no other.
 */
export function emitContextClient(
  context: ContextModel,
  modules: ModuleSet,
  outputDir: string,
): void {
  const module = modules.module(`${context.directory}/client.ts`);
  const name = `${context.typeName}Client`;
  module.imports.add(WOW_CLIENT, ['CommandClient', 'WowClientOptions']);
  module.imports.add('./manifest.js', [`${context.typeName}Manifest`]);
  const fields: string[] = [];
  const assignments: string[] = [];
  for (const aggregate of context.aggregates) {
    const type = aggregate.typeName;
    const at = (file: string) =>
      relativeModuleSpecifier(
        module.directoryPath,
        join(outputDir, aggregate.directory, file),
      );
    const members: [string, string][] = [['commands', `${type}CommandClient`]];
    if (aggregate.queryModels.includes('snapshot')) {
      members.push(['snapshots', `${type}SnapshotQueryClient`]);
    }
    if (aggregate.queryModels.includes('events')) {
      members.push(['events', `${type}EventQueryClient`]);
    }
    members.push(['state', `${type}StateClient`]);
    module.imports.add(at('commandClient.ts'), [`${type}CommandClient`]);
    module.imports.add(
      at('queryClient.ts'),
      members.slice(1).map(([, client]) => client),
    );
    const property = resolvePropertyName(camelCase(aggregate.name));
    fields.push(
      `${doc([`The clients of \`${aggregate.context}.${aggregate.name}\`.`], '  ')}  readonly ${property}: {\n${members.map(([member, client]) => `    readonly ${member}: ${client};`).join('\n')}\n  };`,
    );
    assignments.push(
      `    this.${property} = {\n${members.map(([member, client]) => `      ${member}: new ${client}(options),`).join('\n')}\n    };`,
    );
  }
  if (context.routes.length > 0) {
    module.imports.add('./routes.js', [`${context.typeName}Routes`]);
    fields.push(
      `${doc([`The service routes of \`${context.name}\`.`], '  ')}  readonly routes: ${context.typeName}Routes;`,
    );
    assignments.push(
      `    this.routes = new ${context.typeName}Routes(options);`,
    );
  }
  addStatement(
    module,
    `${doc([
      context.routes.length === 0
        ? `The clients of \`${context.name}\`: its command client, and per aggregate its command, query and state clients.`
        : `The clients of \`${context.name}\`: its command client, per aggregate its command, query and state clients, and its service routes.`,
    ])}export class ${name} {\n  /** Sends any command of the service, by its Manifest's routes: \`send\`, \`wait\` and \`stream\`. */\n  readonly commands: CommandClient;\n\n${fields.join('\n\n')}\n\n  /** @param options - How the service is reached */\n  constructor(options?: WowClientOptions) {\n    this.commands = new CommandClient(options, { manifest: ${context.typeName}Manifest });\n${assignments.join('\n')}\n  }\n}`,
  );
}
