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
import type {
  ContextModel,
  Route,
  RoutingCommandModel,
} from '../../manifest/model';
import { quoteStringLiteral } from '../../naming/naming';
import { addStatement, doc, WOW_CLIENT } from './generatedModule';

/**
 * The routing part of the service's Manifest, as data: every command each
 * aggregate lists with routes, its system commands among them, by its name
 * and its former names, the context's own commands (a job's), and the
 * property of the body that names the aggregate of a command whose body does:
 * what a `CommandClient` over the whole Manifest routes by.
 * Its clients send by it, so they fetch no Manifest; it holds no schema.
 * It keeps the markers (`requires`) of what it keeps, where they stand: the
 * Manifest's (`requires`, the top level's), the context's and each
 * aggregate's, so the client's `checkManifest` refuses what it does not
 * understand.
 */
export function emitRouting(
  context: ContextModel,
  modules: ModuleSet,
  requires?: readonly string[],
): void {
  const module = modules.module(`${context.directory}/manifest.ts`);
  const name = `${context.typeName}Manifest`;
  module.imports.add(WOW_CLIENT, ['MANIFEST_FORMAT', 'RoutingManifest']);
  const command = (item: RoutingCommandModel, indent: string) =>
    `${indent}{ name: ${quoteStringLiteral(item.name)}${item.aliases === undefined ? '' : `, aliases: [${item.aliases.map(quoteStringLiteral).join(', ')}]`}, routes: ${routesLiteral(item.routes)}${item.idProperty === undefined ? '' : `, idProperty: ${quoteStringLiteral(item.idProperty)}`} },`;
  const aggregates = context.aggregates.map(aggregate => {
    const commands = aggregate.routing.map(item => command(item, '          '));
    return `      {\n        name: ${quoteStringLiteral(aggregate.name)},\n${requiresMember(aggregate.requires, '        ')}        commands: [\n${commands.join('\n')}\n        ],\n      },`;
  });
  const contextCommands =
    context.commands.length === 0
      ? ''
      : `      commands: [\n${context.commands.map(item => command(item, '        ')).join('\n')}\n      ],\n`;
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
    ])}export const ${name}: RoutingManifest = {\n  format: MANIFEST_FORMAT,\n${requiresMember(requires, '  ')}  contexts: [\n    {\n      name: ${quoteStringLiteral(context.name)},\n${requiresMember(context.requires, '      ')}      aggregates: [\n${aggregates.join('\n')}\n      ],\n${contextCommands}${routesMember}    },\n  ],\n};`,
  );
}

/** The `requires` member of markers at `indent`, a line of its own; none for none. */
function requiresMember(
  requires: readonly string[] | undefined,
  indent: string,
): string {
  return requires === undefined || requires.length === 0
    ? ''
    : `${indent}requires: [${requires.map(quoteStringLiteral).join(', ')}],\n`;
}

/** The routes a generated method sends its command by, as a literal. */
function routesLiteral(routes: readonly Route[]): string {
  return `[${routes.map(route => `{ method: ${quoteStringLiteral(route.method)}, path: ${quoteStringLiteral(route.path)} }`).join(', ')}]`;
}
