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
import type { ContextModel } from '../../manifest/model';
import type { ServiceRouteModel } from '../../manifest/serviceRoutes';
import { quoteStringLiteral, resolvePropertyName } from '../../naming/naming';
import {
  addStatement,
  doc,
  importTypes,
  type TypeFiles,
  WOW_CLIENT,
  WOW_CLIENT_ROUTES,
} from './generatedModule';
import { routeNumbersOption } from './textNumbers';

/**
 * A context's service routes as one class, a method per route named as the
 * Manifest names it: its path variables in order, then its body, then an
 * object of its query parameters (required when one of them is), then the
 * request's options; it answers the route's answer (`| undefined` when it
 * may answer 204 for it), or nothing for a route that answers 204 alone.
 */
export function emitServiceRoutes(
  context: ContextModel,
  modules: ModuleSet,
  files: TypeFiles,
  outputDir: string,
): void {
  if (context.routes.length === 0) return;
  const module = modules.module(`${context.directory}/routes.ts`);
  const name = `${context.typeName}Routes`;
  module.imports.add(WOW_CLIENT, ['RequestOptions', 'WowClientOptions']);
  module.imports.add(WOW_CLIENT_ROUTES, ['ServiceRoutes']);
  module.imports.add('./manifest.js', [`${context.typeName}Manifest`]);
  importTypes(
    module,
    outputDir,
    files,
    context.routes.flatMap(route =>
      [
        route.requestType,
        route.responseType,
        ...route.parameters.map(p => p.type),
      ]
        .filter((type): type is string => type !== undefined)
        .flatMap(type => type.match(/[A-Za-z_$][A-Za-z0-9_$]*/g) ?? []),
    ),
  );
  const methods = context.routes.map(route => routeMethod(context, route));
  addStatement(
    module,
    `${doc([
      `The service routes of \`${context.name}\`: routes whose handler is the service's own code, each called by its Manifest's name.`,
    ])}export class ${name} {\n  /** Calls any route of the service by its name. */\n  readonly routes: ServiceRoutes;\n\n  /** @param options - How the service is reached */\n  constructor(options?: WowClientOptions) {\n    this.routes = new ServiceRoutes(options, { manifest: ${context.typeName}Manifest${routeNumbersOption(context, { module, outputDir, client: WOW_CLIENT })} });\n  }\n\n${methods.join('\n\n')}\n}`,
  );
}

/** The method of one service route. */
function routeMethod(context: ContextModel, route: ServiceRouteModel): string {
  const variables = route.parameters.filter(p => p.location === 'path');
  const queries = route.parameters.filter(p => p.location === 'query');
  const parameters = variables.map(p => `${p.identifier}: ${p.type}`);
  if (route.requestType !== undefined)
    parameters.push(`body: ${route.requestType}`);
  if (queries.length > 0) {
    const members = queries
      .map(
        p =>
          `readonly ${resolvePropertyName(p.name)}${p.required ? '' : '?'}: ${p.type}`,
      )
      .join('; ');
    const required = queries.some(p => p.required);
    parameters.push(`query${required ? '' : '?'}: { ${members} }`);
  }
  parameters.push('options?: RequestOptions');
  const answer = route.responseType ?? 'void';
  const call = [
    ...(variables.length === 0
      ? []
      : [
          `path: { ${variables.map(p => (p.identifier === p.name ? p.name : `${resolvePropertyName(p.name)}: ${p.identifier}`)).join(', ')} }`,
        ]),
    ...(route.requestType === undefined ? [] : ['body']),
    ...(queries.length === 0 ? [] : ['query']),
  ];
  const comment = doc(
    [
      route.description,
      `\`${route.method} ${route.path}\``,
      route.deprecated
        ? '@deprecated The service deprecates this route.'
        : undefined,
    ],
    '  ',
  );
  return `${comment}  ${resolvePropertyName(route.name)}(${parameters.join(', ')}): Promise<${answer}> {\n    return this.routes.call(${quoteStringLiteral(context.name)}, ${quoteStringLiteral(route.name)}, { ${call.join(', ')} }, options) as Promise<${answer}>;\n  }`;
}
