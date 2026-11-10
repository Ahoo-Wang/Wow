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
import type { ContextModel, Route } from '../../manifest/model';
import { quoteStringLiteral } from '../../naming/naming';
import { addStatement, doc, WOW_CLIENT } from './generatedModule';

/**
 * The routing part of the service's Manifest, as data: each aggregate's
 * commands, its lifecycle commands among them, with their routes, and the
 * property of the body that names the aggregate of a command whose body does.
 * Its clients send by it, so they fetch no Manifest; it holds no schema.
 */
export function emitRouting(context: ContextModel, modules: ModuleSet): void {
  const module = modules.module(`${context.directory}/manifest.ts`);
  const name = `${context.typeName}Manifest`;
  module.imports.add(WOW_CLIENT, ['MANIFEST_FORMAT', 'RoutingManifest']);
  const command = (
    commandName: string,
    routes: readonly Route[],
    idProperty?: string,
  ) =>
    `          { name: ${quoteStringLiteral(commandName)}, routes: ${routesLiteral(routes)}${idProperty === undefined ? '' : `, idProperty: ${quoteStringLiteral(idProperty)}`} },`;
  const aggregates = context.aggregates.map(aggregate => {
    const commands = [
      ...aggregate.commands
        .filter(item => item.routes.length > 0)
        .map(item => command(item.name, item.routes, item.idProperty)),
      ...aggregate.lifecycle.map(item => command(item.name, item.routes)),
    ];
    return `      {\n        name: ${quoteStringLiteral(aggregate.name)},\n        commands: [\n${commands.join('\n')}\n        ],\n      },`;
  });
  const routes = context.routes.map(
    route =>
      `        { name: ${quoteStringLiteral(route.name)}, route: { method: ${quoteStringLiteral(route.method)}, path: ${quoteStringLiteral(route.path)} } },`,
  );
  const routesMember =
    routes.length === 0
      ? ''
      : `      routes: [\n${routes.join('\n')}\n      ],\n`;
  addStatement(
    module,
    `${doc([
      routes.length === 0
        ? `The routes of \`${context.name}\`'s commands, as its Manifest lists them: what its command clients send by, with no Manifest fetched and no schema.`
        : `The routes of \`${context.name}\`'s commands and service routes, as its Manifest lists them: what its clients send by, with no Manifest fetched and no schema.`,
    ])}export const ${name}: RoutingManifest = {\n  format: MANIFEST_FORMAT,\n  contexts: [\n    {\n      name: ${quoteStringLiteral(context.name)},\n      aggregates: [\n${aggregates.join('\n')}\n      ],\n${routesMember}    },\n  ],\n};`,
  );
}

/** The routes a generated method sends its command by, as a literal. */
function routesLiteral(routes: readonly Route[]): string {
  return `[${routes.map(route => `{ method: ${quoteStringLiteral(route.method)}, path: ${quoteStringLiteral(route.path)} }`).join(', ')}]`;
}
