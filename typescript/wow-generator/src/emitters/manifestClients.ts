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
import { escapeJsDoc } from '../emit/jsdoc';
import type { ModuleBuilder, ModuleSet } from '../emit/moduleBuilder';
import { relativeModuleSpecifier } from '../emit/imports';
import type {
  AggregateModel,
  CommandModel,
  ContextModel,
  ManifestModel,
  Route,
  ServiceRouteModel,
  TypeModel,
  TextNumbersModel,
} from '../manifest/analyze';
import {
  camelCase,
  quoteStringLiteral,
  resolvePropertyName,
  upperSnakeCase,
} from '../naming/naming';

/** The entry of the client every generated module imports from. */
export const WOW_CLIENT = '@ahoo-wang/wow-client';

/** The entry of the service routes' client. */
export const WOW_CLIENT_ROUTES = '@ahoo-wang/wow-client/routes';

/** A doc comment of the lines given, those without text left out. */
function doc(lines: readonly (string | undefined)[], indent = ''): string {
  const text = lines
    .filter((line): line is string => typeof line === 'string' && line !== '')
    .flatMap(line => escapeJsDoc(line).split('\n'));
  if (text.length === 0) return '';
  if (text.length === 1) return `${indent}/** ${text[0]} */\n`;
  return `${indent}/**\n${text.map(line => `${indent} * ${line}`.trimEnd()).join('\n')}\n${indent} */\n`;
}

/**
 * Adds a statement set off from the one before by a blank line, as the
 * structures the other emitters add are.
 */
function addStatement(module: ModuleBuilder, statement: string): void {
  module.add(`\n${statement}`);
}

function union(members: readonly string[]): string {
  return members.length === 0 ? 'never' : members.join(' | ');
}

/** Where each generated type is declared, by its name. */
type TypeFiles = ReadonlyMap<string, string>;

/**
 * Imports the generated types a module names from the files declaring them.
 */
function importTypes(
  module: ModuleBuilder,
  outputDir: string,
  files: TypeFiles,
  names: readonly string[],
  ownFile?: string,
): void {
  const byFile = new Map<string, string[]>();
  for (const name of new Set(names)) {
    const file = files.get(name);
    if (file === undefined || file === ownFile) continue;
    byFile.set(file, [...(byFile.get(file) ?? []), name]);
  }
  for (const [file, imported] of byFile) {
    module.imports.add(
      relativeModuleSpecifier(module.directoryPath, join(outputDir, file)),
      imported,
    );
  }
}

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
    emitServiceRoutes(context, modules, files, outputDir);
    emitContextClient(context, modules, outputDir);
  }
}

function emitType(
  module: ModuleBuilder,
  type: TypeModel,
  files: TypeFiles,
  outputDir: string,
): void {
  importTypes(module, outputDir, files, type.references, type.file);
  const comment = doc([type.description, `- key: \`${type.key}\``]);
  if (type.properties === undefined) {
    addStatement(module, `${comment}export type ${type.name} = ${type.type};`);
    return;
  }
  const members = type.properties.map(
    property =>
      `${doc([property.description], '  ')}  ${property.name}${property.optional ? '?' : ''}: ${property.type};`,
  );
  if (type.type !== undefined) members.push(`  [key: string]: ${type.type};`);
  addStatement(
    module,
    `${comment}export interface ${type.name} {\n${members.join('\n')}\n}`,
  );
}

/** An error of a context's Manifest, by the constant it is named by. */
type ErrorEntry = ContextModel['errors'][number];

/**
 * The errors of `aggregate`, those whose code is `context.aggregate.*`, or,
 * without one, the context's own: each by the constant naming what follows
 * its owner's name, as the Kotlin contract names them.
 */
function errorsOf(
  context: ContextModel,
  aggregate?: AggregateModel,
): { readonly member: string; readonly error: ErrorEntry }[] {
  const owners = context.aggregates.map(
    each => `${context.name}.${each.name}.`,
  );
  const prefix =
    aggregate === undefined
      ? `${context.name}.`
      : `${context.name}.${aggregate.name}.`;
  return context.errors
    .filter(error =>
      aggregate === undefined
        ? !owners.some(owner => error.code.startsWith(owner))
        : error.code.startsWith(prefix),
    )
    .map(error => ({
      member:
        upperSnakeCase(
          (error.code.startsWith(prefix)
            ? error.code.slice(prefix.length)
            : error.code
          ).split(/[^A-Za-z0-9]+/),
        ) || 'ERROR',
      error,
    }));
}

/** The `Errors` member of a contract constant, or nothing when it has none. */
function errorsMember(
  errors: ReturnType<typeof errorsOf>,
  indent: string,
): string {
  if (errors.length === 0) return '';
  const members = errors.map(
    ({ member, error }) =>
      `${doc([`${error.status}: ${error.description ?? error.code}`], `${indent}  `)}${indent}  ${member}: ${quoteStringLiteral(error.code)},`,
  );
  return `${indent}Errors: {\n${members.join('\n')}\n${indent}},\n`;
}

function emitContract(
  context: ContextModel,
  modules: ModuleSet,
  outputDir: string,
): void {
  const module = modules.module(`${context.directory}/contract.ts`);
  const contract = `${context.typeName}Contract`;
  module.imports.add(WOW_CLIENT, ['Hop', 'wait']);
  for (const aggregate of context.aggregates) {
    module.imports.add(
      relativeModuleSpecifier(
        module.directoryPath,
        join(outputDir, aggregate.directory, 'aggregate.ts'),
      ),
      [`${aggregate.typeName}Aggregate`],
    );
  }
  const aggregates = context.aggregates.map(
    aggregate => `  ${aggregate.typeName}: ${aggregate.typeName}Aggregate,\n`,
  );
  addStatement(
    module,
    `${doc([
      `The bounded context \`${context.name}\`: its name, its error codes and its aggregates, as the Kotlin \`${contract}\` names them. A problem's \`code\` is one of its \`Errors\`, or of an aggregate's.`,
      context.description,
    ])}export const ${contract} = {\n  NAME: ${quoteStringLiteral(context.name)},\n${errorsMember(errorsOf(context), '  ')}${aggregates.join('')}} as const;`,
  );
  addStatement(
    module,
    `${doc([`An error code of \`${context.name}\`, of the context or of one of its aggregates.`])}export type ${context.typeName}ErrorCode = ${union(context.errors.map(error => quoteStringLiteral(error.code)))};`,
  );
  const hasSubscriptions = context.subscriptions.length > 0;
  if (hasSubscriptions) {
    addStatement(
      module,
      `${doc([`The subscriptions of \`${context.name}\` a \`handled\` hop names.`])}export type ${context.typeName}Subscription = ${union(context.subscriptions.map(quoteStringLiteral))};`,
    );
  }
  const name = `${contract}.NAME`;
  const hops = [
    `  /** Until a command of \`${context.name}\` passed admission. */\n  accepted: (): Hop => wait.accepted(${name}),`,
    `  /** Until a command's events are appended to the log of \`${context.name}\`. */\n  committed: (): Hop => wait.committed(${name}),`,
    `  /** Until the snapshot of \`${context.name}\` holds the commit. */\n  snapshot: (): Hop => wait.snapshot(${name}),`,
  ];
  if (hasSubscriptions) {
    hops.push(
      `  /** Until a subscription of \`${context.name}\` handled the commit. */\n  handled: (subscription: ${context.typeName}Subscription): Hop => wait.handled(subscription, ${name}),`,
    );
  }
  addStatement(
    module,
    `${doc([`The hops of a wait chain (\`Wow-Wait\`) in \`${context.name}\`.`])}export const ${camelCase(context.name)}Wait = {\n${hops.join('\n')}\n} as const;`,
  );
}

/**
 * The routing part of the service's Manifest, as data: each aggregate's
 * commands, its lifecycle commands among them, with their routes. Its clients
 * send by it, so they fetch no Manifest; it holds no schema.
 */
function emitRouting(context: ContextModel, modules: ModuleSet): void {
  const module = modules.module(`${context.directory}/manifest.ts`);
  const name = `${context.typeName}Manifest`;
  module.imports.add(WOW_CLIENT, ['MANIFEST_FORMAT', 'RoutingManifest']);
  const command = (commandName: string, routes: readonly Route[]) =>
    `          { name: ${quoteStringLiteral(commandName)}, routes: ${routesLiteral(routes)} },`;
  const aggregates = context.aggregates.map(aggregate => {
    const commands = [
      ...aggregate.commands
        .filter(item => item.routes.length > 0)
        .map(item => command(item.name, item.routes)),
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

/**
 * A context's service routes as one class, a method per route named as the
 * Manifest names it: its path variables in order, then its body, then an
 * object of its query parameters (required when one of them is), then the
 * request's options; it answers the route's answer, or nothing for a route
 * that answers 204.
 */
function emitServiceRoutes(
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
    ])}export class ${name} {\n  /** Calls any route of the service by its name. */\n  readonly routes: ServiceRoutes;\n\n  /** @param options - How the service is reached */\n  constructor(options?: WowClientOptions) {\n    this.routes = new ServiceRoutes(options, { manifest: ${context.typeName}Manifest });\n  }\n\n${methods.join('\n\n')}\n}`,
  );
}

/** The method of one service route. */
function routeMethod(context: ContextModel, route: ServiceRouteModel): string {
  const variables = route.parameters.filter(p => p.location === 'path');
  const queries = route.parameters.filter(p => p.location === 'query');
  const parameters = variables.map(p => `${p.name}: ${p.type}`);
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
          `path: { ${variables.map(p => resolvePropertyName(p.name)).join(', ')} }`,
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

/**
 * The clients of a context in one place: per aggregate its command client and
 * its query and state clients, on the same options. Each client stays its own
 * module too, so an application that imports a single client loads no other.
 */
function emitContextClient(
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

/** A TypeScript literal of exact-number marks: `{ price: true, lines: { '*': { amount: true } } }`. */
function textNumbersLiteral(marks: TextNumbersModel): string {
  if (marks === true) return 'true';
  return `{ ${Object.entries(marks)
    .map(
      ([member, mark]) =>
        `${resolvePropertyName(member)}: ${textNumbersLiteral(mark)}`,
    )
    .join(', ')} }`;
}

/** The `textNumbers` member of an aggregate constant, or nothing when it has none. */
function textNumbersMember(aggregate: AggregateModel): string {
  const numbers = aggregate.textNumbers;
  if (numbers === undefined) return '';
  const groups = (['state', 'events', 'commands'] as const).flatMap(group => {
    const value = numbers[group];
    if (value === undefined) return [];
    if (group === 'state')
      return [`    state: ${textNumbersLiteral(value as TextNumbersModel)},`];
    const entries = Object.entries(
      value as Readonly<Record<string, TextNumbersModel>>,
    ).map(
      ([name, mark]) =>
        `      ${resolvePropertyName(name)}: ${textNumbersLiteral(mark)},`,
    );
    return [`    ${group}: {\n${entries.join('\n')}\n    },`];
  });
  return `  textNumbers: {\n${groups.join('\n')}\n  },\n`;
}

function emitAggregate(
  aggregate: AggregateModel,
  context: ContextModel,
  modules: ModuleSet,
  files: TypeFiles,
  outputDir: string,
): void {
  const module = modules.module(`${aggregate.directory}/aggregate.ts`);
  const name = aggregate.typeName;
  addStatement(
    module,
    `${doc([
      `The aggregate \`${aggregate.context}.${aggregate.name}\`.`,
      aggregate.description,
    ])}export const ${name}Aggregate = {\n  context: ${quoteStringLiteral(context.name)},\n  name: ${quoteStringLiteral(aggregate.name)},\n  path: ${quoteStringLiteral(`/${context.name}/${aggregate.name}`)},\n  ownership: ${quoteStringLiteral(aggregate.ownership)},\n${aggregate.idSource === 'assigned' ? '' : `  idSource: ${quoteStringLiteral(aggregate.idSource)},\n`}  snapshot: ${aggregate.snapshot},\n${textNumbersMember(aggregate)}${errorsMember(errorsOf(context, aggregate), '  ')}} as const;`,
  );
  addStatement(
    module,
    `${doc([
      `The \`state.*\` paths of \`${aggregate.name}\`'s snapshots a query filters, sorts and projects on.`,
    ])}export type ${name}StateField = ${union(aggregate.stateFields.map(quoteStringLiteral))};`,
  );
  const events = aggregate.events.map(
    event =>
      `CommittedEvent<${quoteStringLiteral(event.wireName)}, ${event.type}>`,
  );
  module.imports.add(
    WOW_CLIENT,
    events.length > 0 ? ['CommittedEvent', 'SystemEvent'] : ['SystemEvent'],
  );
  addStatement(
    module,
    `${doc([
      `The events of \`${aggregate.name}\`, named as the server sends them, \`${aggregate.context}.${aggregate.name}.<Event>\` at its current revision (the \`events\` query upgrades older records), and the system events every aggregate has.`,
    ])}export type ${name}Event = ${union([...events, 'SystemEvent'])};`,
  );
  importTypes(
    module,
    outputDir,
    files,
    aggregate.events.map(event => event.type),
  );
}

/**
 * The doc of a command's method: by every route it has, or, of an aggregate
 * whose id is its owner's, by id or by no id to the caller's own ([own]).
 */
function commandDoc(command: CommandModel, own?: boolean): string {
  const paths =
    own === undefined
      ? [command.byIdPath, command.unaddressedPath]
      : [own ? command.unaddressedPath : command.byIdPath];
  const routes = paths
    .filter(path => path !== undefined)
    .map(path => `\`POST ${path.path}\``)
    .join(', ');
  const creates =
    command.creation === 'required'
      ? 'Creates the aggregate.'
      : command.creation === 'allowed'
        ? 'Creates the aggregate, or acts on the one that exists.'
        : undefined;
  return doc(
    [
      command.description,
      `\`${command.name}\`${command.mounted ? ', mounted (a transformer finds its aggregate)' : ''}${own === true ? ", to the caller's own aggregate, whose id is the caller's owner" : ''}: ${routes}.`,
      own === false
        ? 'By id, for a caller with no owner; `' +
          command.ownMethod +
          "` sends it to the caller's own."
        : undefined,
      creates,
      command.produces.length > 0
        ? `Produces ${command.produces.map(event => `\`${event}\``).join(', ')}.`
        : undefined,
    ],
    '  ',
  );
}

/** The routes a generated method sends its command by, as a literal. */
function routesLiteral(routes: readonly Route[]): string {
  return `[${routes.map(route => `{ method: ${quoteStringLiteral(route.method)}, path: ${quoteStringLiteral(route.path)} }`).join(', ')}]`;
}

/**
 * The method that sends [command] (its stream variant with [stream]); of an
 * aggregate whose id is its owner's, the one by id or, with [own], the one by
 * no id to the caller's own.
 */
function commandMethods(
  command: CommandModel,
  aggregate: AggregateModel,
  stream: boolean,
  own?: boolean,
): string {
  const body = command.bodyType === 'unknown' ? 'object' : command.bodyType;
  const bodyParam = command.bodyOptional
    ? `body: ${body} = {}`
    : `body: ${body}`;
  const call = stream ? 'stream' : 'wait';
  const returns = stream
    ? 'Promise<CommandResultStream>'
    : 'Promise<CommandResult>';
  const method = own === true ? command.ownMethod! : command.method;
  const name = stream ? `${method}Stream` : method;
  const comment = stream
    ? doc(
        [
          `\`${command.name}\`${own === true ? " to the caller's own aggregate" : ''}, answered as one result per hop of its wait (\`text/event-stream\`).`,
        ],
        '  ',
      )
    : commandDoc(command, own);
  const fullName = quoteStringLiteral(
    `${aggregate.context}.${aggregate.name}.${command.name}`,
  );
  const request = (id: string) =>
    `{ ...options, command: ${fullName}, body${id} }`;
  // Of an aggregate whose id is its owner's, one method by id and one by no id; else one by the routes it has.
  const byId = own === undefined ? command.byIdPath : !own;
  const unaddressed = own === undefined ? command.unaddressedPath : own;
  const options =
    byId && unaddressed ? 'CreateCommandOptions' : 'CommandOptions';
  const idParam = byId && !unaddressed ? 'id: string, ' : '';
  const sent = request(byId && !unaddressed ? ', id' : '');
  if (stream) {
    return `${comment}  ${name}(${idParam}${bodyParam}, options?: ${options}): ${returns} {\n    return this.client.${call}(${sent});\n  }`;
  }
  return answeringMethod(
    comment,
    name,
    `${idParam}${bodyParam}`,
    options,
    sent,
  );
}

/** A method that answers a command's result, sending [request] with the method's own [params]. */
function answeringMethod(
  comment: string,
  name: string,
  params: string,
  options: string,
  request: string,
): string {
  const leading = params ? `${params}, ` : '';
  return `${comment}  ${name}(${leading}options?: ${options}): Promise<CommandResult> {\n    return this.client.wait(${request});\n  }`;
}

function emitCommandClient(
  aggregate: AggregateModel,
  context: ContextModel,
  modules: ModuleSet,
  files: TypeFiles,
  outputDir: string,
): void {
  const module = modules.module(`${aggregate.directory}/commandClient.ts`);
  const name = aggregate.typeName;
  const commands = aggregate.commands.filter(
    command => command.byIdPath || command.unaddressedPath,
  );
  const methods = commands.flatMap(command =>
    command.ownMethod === undefined
      ? [
          commandMethods(command, aggregate, false),
          commandMethods(command, aggregate, true),
        ]
      : [false, true].flatMap(own => [
          commandMethods(command, aggregate, false, own),
          commandMethods(command, aggregate, true, own),
        ]),
  );
  const client = ['CommandClient', 'CommandResult', 'WowClientOptions'];
  if (commands.length > 0) client.push('CommandResultStream');
  const both = (command: CommandModel) =>
    command.byIdPath !== undefined &&
    command.unaddressedPath !== undefined &&
    command.ownMethod === undefined;
  if (commands.some(both)) {
    client.push('CreateCommandOptions');
  }
  if (
    commands.some(command => !both(command)) ||
    aggregate.lifecycle.length > 0
  ) {
    client.push('CommandOptions');
  }
  const lifecycleTypes: string[] = [];
  for (const lifecycle of aggregate.lifecycle) {
    client.push('LifecycleCommands');
    if (lifecycle.bodyType) {
      (lifecycle.bodyGenerated ? lifecycleTypes : client).push(
        lifecycle.bodyType,
      );
    }
    const body = lifecycle.bodyType ? `body: ${lifecycle.bodyType}` : '';
    const request = (id: boolean) =>
      `{ ...options, command: LifecycleCommands.${lifecycle.member}, type: { context: ${quoteStringLiteral(aggregate.context)}, name: ${quoteStringLiteral(aggregate.name)} }${id ? ', id' : ''}${lifecycle.bodyType ? ', body' : ''} }`;
    methods.push(
      answeringMethod(
        doc(
          [
            lifecycle.description,
            `The lifecycle command \`${lifecycle.name}\`.`,
            lifecycle.ownMethod === undefined
              ? undefined
              : `By id, for a caller with no owner; \`${lifecycle.ownMethod}\` sends it to the caller's own.`,
          ],
          '  ',
        ),
        lifecycle.method,
        body ? `id: string, ${body}` : 'id: string',
        'CommandOptions',
        request(true),
      ),
    );
    if (lifecycle.ownMethod !== undefined) {
      methods.push(
        answeringMethod(
          doc(
            [
              lifecycle.description,
              `The lifecycle command \`${lifecycle.name}\`, to the caller's own aggregate, whose id is the caller's owner.`,
            ],
            '  ',
          ),
          lifecycle.ownMethod,
          body,
          'CommandOptions',
          request(false),
        ),
      );
    }
  }
  // A body with exact numbers sends them unquoted: its marks go with the client, by the command's full name.
  const marked = commands.filter(command => command.bodyNumbers);
  if (marked.length > 0) {
    module.imports.add('./aggregate.js', [`${name}Aggregate`]);
  }
  const bodyNumbers = marked.length
    ? `, bodyNumbers: {\n${marked.map(command => `      ${quoteStringLiteral(`${aggregate.context}.${aggregate.name}.${command.name}`)}: ${name}Aggregate.textNumbers.commands.${resolvePropertyName(command.name) === command.name ? command.name : `[${quoteStringLiteral(command.name)}]`},`).join('\n')}\n    }`
    : '';
  const manifest = `${context.typeName}Manifest`;
  module.imports.add(
    relativeModuleSpecifier(
      module.directoryPath,
      join(outputDir, context.directory, 'manifest.ts'),
    ),
    [manifest],
  );
  module.imports.add(WOW_CLIENT, [...new Set(client)].sort());
  importTypes(module, outputDir, files, [
    ...commands.map(command => command.bodyType),
    ...lifecycleTypes,
  ]);
  addStatement(
    module,
    `${doc([
      `Sends the commands of \`${aggregate.context}.${aggregate.name}\` by its service's routes (\`${manifest}\`). Each method answers the result its wait plan ends with, as \`CommandClient.wait\` does: the server's default plan unless the options give one, \`{ wait: WaitPlan.ACCEPTED }\` to answer once it is accepted. A rejected command rejects with a \`CommandRejectedError\`; anything else that did not succeed with a \`WowProblemError\`.`,
    ])}export class ${name}CommandClient {\n  readonly client: CommandClient;\n\n  /** @param options - How the service is reached */\n  constructor(options?: WowClientOptions) {\n    this.client = new CommandClient(options, { manifest: ${manifest}${bodyNumbers} });\n  }\n\n${methods.join('\n\n')}\n}`,
  );
}

function emitQueryClient(
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
  const aggregateNames = [`${name}Aggregate`, `${name}Event`];
  const constructor = `  /** @param options - How the service is reached */\n  constructor(options?: WowClientOptions) {\n    super(${name}Aggregate, options);\n  }`;
  if (aggregate.queryModels.includes('snapshot')) {
    client.push('SnapshotQueryClient');
    aggregateNames.push(`${name}StateField`);
    addStatement(
      module,
      `${doc([`Queries the snapshots of \`${aggregate.context}.${aggregate.name}\`: \`POST /${aggregate.context}/${aggregate.name}/snapshot/query\`.`])}export class ${name}SnapshotQueryClient extends SnapshotQueryClient<${state}, ${name}StateField> {\n${constructor}\n}`,
    );
  }
  if (aggregate.queryModels.includes('events')) {
    client.push('EventQueryClient');
    addStatement(
      module,
      `${doc([`Queries the event streams of \`${aggregate.context}.${aggregate.name}\`, one record per commit: \`POST /${aggregate.context}/${aggregate.name}/events/query\`.`])}export class ${name}EventQueryClient extends EventQueryClient<${name}Event> {\n${constructor}\n}`,
    );
  }
  addStatement(
    module,
    `${doc([
      owned
        ? `Reads the states of \`${aggregate.context}.${aggregate.name}\`, at a version or a time, and their traces: the caller's own by no id (\`readOwn\`, \`getOwn\`, \`findOwn\`, \`traceOwn\`: \`GET /${aggregate.context}/${aggregate.name}/state\`), and by id for a caller with no owner (\`GET /${aggregate.context}/${aggregate.name}/{id}/state\`).`
        : `Reads the states of \`${aggregate.context}.${aggregate.name}\`, at a version or a time, and their traces: \`GET /${aggregate.context}/${aggregate.name}/{id}/state\`.`,
    ])}export class ${name}StateClient extends ${stateClient}<${state}, ${name}Event> {\n${constructor}\n}`,
  );
  module.imports.add(WOW_CLIENT, client.sort());
  module.imports.add('./aggregate.js', aggregateNames);
  importTypes(module, outputDir, files, [state]);
}
