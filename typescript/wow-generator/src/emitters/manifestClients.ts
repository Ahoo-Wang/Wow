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
  TypeModel,
} from '../manifest/analyze';
import { camelCase, quoteStringLiteral } from '../naming/naming';

/** The entry of the client every generated module imports from. */
export const WOW_CLIENT = '@ahoo-wang/wow-client';

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
 * Describes every file a Manifest generates to its module (decision D74,
 * roadmap 5a.3): the types of each bounded context, its error codes and wait
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
    emitBoundedContext(context, modules);
    for (const aggregate of context.aggregates) {
      emitAggregate(aggregate, context, modules, files, outputDir);
      emitCommandClient(aggregate, modules, files, outputDir);
      emitQueryClient(aggregate, modules, files, outputDir);
    }
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

function emitBoundedContext(context: ContextModel, modules: ModuleSet): void {
  const module = modules.module(`${context.directory}/boundedContext.ts`);
  const constant = `${context.constantName}_BOUNDED_CONTEXT`;
  module.imports.add(WOW_CLIENT, ['Hop', 'wait']);
  addStatement(
    module,
    `${doc([`The bounded context \`${context.name}\`.`, context.description])}export const ${constant} = ${quoteStringLiteral(context.name)};`,
  );
  const errors = context.errors.map(
    error =>
      `${doc([`${error.status}: ${error.description ?? error.code}`], '  ')}  ${error.member}: ${quoteStringLiteral(error.code)},`,
  );
  addStatement(
    module,
    `${doc([
      `The domain error codes of \`${context.name}\`, each a problem's \`code\` with the status the Manifest gives it.`,
    ])}export const ${context.typeName}ErrorCodes = {\n${errors.join('\n')}\n} as const;`,
  );
  addStatement(
    module,
    `${doc([`A {@link ${context.typeName}ErrorCodes} value.`])}export type ${context.typeName}ErrorCode = (typeof ${context.typeName}ErrorCodes)[keyof typeof ${context.typeName}ErrorCodes];`,
  );
  const hasSubscriptions = context.subscriptions.length > 0;
  if (hasSubscriptions) {
    addStatement(
      module,
      `${doc([`The subscriptions of \`${context.name}\` a \`handled\` hop names.`])}export type ${context.typeName}Subscription = ${union(context.subscriptions.map(quoteStringLiteral))};`,
    );
  }
  const hops = [
    `  /** Until a command of \`${context.name}\` passed admission. */\n  accepted: (): Hop => wait.accepted(${constant}),`,
    `  /** Until a command's events are appended to the log of \`${context.name}\`. */\n  committed: (): Hop => wait.committed(${constant}),`,
    `  /** Until the snapshot of \`${context.name}\` holds the commit. */\n  snapshot: (): Hop => wait.snapshot(${constant}),`,
  ];
  if (hasSubscriptions) {
    hops.push(
      `  /** Until a subscription of \`${context.name}\` handled the commit. */\n  handled: (subscription: ${context.typeName}Subscription): Hop => wait.handled(subscription, ${constant}),`,
    );
  }
  addStatement(
    module,
    `${doc([`The hops of a wait chain (\`Wow-Wait\`) in \`${context.name}\`.`])}export const ${camelCase(context.name)}Wait = {\n${hops.join('\n')}\n} as const;`,
  );
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
    ])}export const ${name}Aggregate = {\n  context: ${quoteStringLiteral(context.name)},\n  name: ${quoteStringLiteral(aggregate.name)},\n  path: ${quoteStringLiteral(`/${context.name}/${aggregate.name}`)},\n  ownership: ${quoteStringLiteral(aggregate.ownership)},\n  snapshot: ${aggregate.snapshot},\n} as const;`,
  );
  addStatement(
    module,
    `${doc([
      `The \`state.*\` paths of \`${aggregate.name}\`'s snapshots a query filters, sorts and projects on.`,
    ])}export type ${name}StateField = ${union(aggregate.stateFields.map(quoteStringLiteral))};`,
  );
  const events = aggregate.events.map(
    event => `EventView<${quoteStringLiteral(event.wireName)}, ${event.type}>`,
  );
  module.imports.add(
    WOW_CLIENT,
    events.length > 0 ? ['EventView', 'SystemEvent'] : ['SystemEvent'],
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

function commandDoc(command: CommandModel): string {
  const routes = [command.byIdPath, command.unaddressedPath]
    .filter(path => path !== undefined)
    .map(path => `\`POST ${path.path}\``)
    .join(', ');
  const creates =
    command.creation === 'REQUIRED'
      ? 'Creates the aggregate.'
      : command.creation === 'ALLOWED'
        ? 'Creates the aggregate, or acts on the one that exists.'
        : undefined;
  return doc(
    [
      command.description,
      `\`${command.name}\`${command.mounted ? ', mounted (a transformer finds its aggregate)' : ''}: ${routes}.`,
      creates,
      command.produces.length > 0
        ? `Produces ${command.produces.map(event => `\`${event}\``).join(', ')}.`
        : undefined,
    ],
    '  ',
  );
}

function commandMethods(
  command: CommandModel,
  paths: string,
  stream: boolean,
): string {
  const body = command.bodyType === 'unknown' ? 'object' : command.bodyType;
  const bodyParam = command.bodyOptional
    ? `body: ${body} = {}`
    : `body: ${body}`;
  const call = stream ? 'stream' : 'send';
  const returns = stream
    ? 'Promise<CommandResultStream>'
    : 'Promise<CommandResult>';
  const name = stream ? `${command.method}Stream` : command.method;
  const comment = stream
    ? doc(
        [
          `\`${command.name}\`, answered as one result per hop of its wait (\`text/event-stream\`).`,
        ],
        '  ',
      )
    : commandDoc(command);
  const byId = command.byIdPath;
  const unaddressed = command.unaddressedPath;
  if (byId && unaddressed) {
    return `${comment}  ${name}(${bodyParam}, options?: CreateCommandOptions): ${returns} {\n    return this.gateway.${call}(options?.id === undefined ? ${paths}.${unaddressed.member} : ${paths}.${byId.member}, body, options);\n  }`;
  }
  if (byId) {
    return `${comment}  ${name}(id: string, ${bodyParam}, options?: CommandOptions): ${returns} {\n    return this.gateway.${call}(${paths}.${byId.member}, body, { ...options, id });\n  }`;
  }
  return `${comment}  ${name}(${bodyParam}, options?: CommandOptions): ${returns} {\n    return this.gateway.${call}(${paths}.${unaddressed!.member}, body, options);\n  }`;
}

function emitCommandClient(
  aggregate: AggregateModel,
  modules: ModuleSet,
  files: TypeFiles,
  outputDir: string,
): void {
  const module = modules.module(`${aggregate.directory}/commandClient.ts`);
  const name = aggregate.typeName;
  const paths = `${name}CommandPaths`;
  const commands = aggregate.commands.filter(
    command => command.byIdPath || command.unaddressedPath,
  );
  const members = commands.flatMap(command =>
    [command.byIdPath, command.unaddressedPath]
      .filter(path => path !== undefined)
      .map(path => `  ${path.member}: ${quoteStringLiteral(path.path)},`),
  );
  addStatement(
    module,
    `${doc([`The command routes of \`${aggregate.context}.${aggregate.name}\`.`])}export const ${paths} = {\n${members.join('\n')}\n} as const;`,
  );
  const methods = commands.flatMap(command => [
    commandMethods(command, paths, false),
    commandMethods(command, paths, true),
  ]);
  const client = ['CommandGateway', 'CommandResult', 'WowClientOptions'];
  if (commands.length > 0) client.push('CommandResultStream');
  if (commands.some(command => command.byIdPath && command.unaddressedPath)) {
    client.push('CreateCommandOptions');
  }
  if (
    commands.some(command => !(command.byIdPath && command.unaddressedPath)) ||
    aggregate.lifecycle.length > 0
  ) {
    client.push('CommandOptions');
  }
  const lifecycleTypes: string[] = [];
  for (const lifecycle of aggregate.lifecycle) {
    client.push('LifecycleCommands');
    if (lifecycle.bodyType) {
      (lifecycle.bodyGenerated ? lifecycleTypes : client).push(lifecycle.bodyType);
    }
    const params = lifecycle.bodyType
      ? `id: string, body: ${lifecycle.bodyType}, options?: CommandOptions`
      : `id: string, options?: CommandOptions`;
    methods.push(
      `${doc([lifecycle.description, `The lifecycle command \`${lifecycle.name}\`.`], '  ')}  ${lifecycle.method}(${params}): Promise<CommandResult> {\n    return this.gateway.lifecycle(${name}Aggregate.path, id, LifecycleCommands.${lifecycle.member}, ${lifecycle.bodyType ? 'body' : 'undefined'}, options);\n  }`,
    );
  }
  if (aggregate.lifecycle.length > 0) {
    module.imports.add('./aggregate.js', [`${name}Aggregate`]);
  }
  module.imports.add(WOW_CLIENT, [...new Set(client)].sort());
  importTypes(module, outputDir, files, [
    ...commands.map(command => command.bodyType),
    ...lifecycleTypes,
  ]);
  addStatement(
    module,
    `${doc([
      `Sends the commands of \`${aggregate.context}.${aggregate.name}\`; a command that did not succeed rejects with a \`WowProblemError\` holding its result.`,
    ])}export class ${name}CommandClient {\n  readonly gateway: CommandGateway;\n\n  /** @param options - How the service is reached */\n  constructor(options?: WowClientOptions) {\n    this.gateway = new CommandGateway(options);\n  }\n\n${methods.join('\n\n')}\n}`,
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
  const client = ['StateClient', 'WowClientOptions'];
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
    `${doc([`Reads the states of \`${aggregate.context}.${aggregate.name}\`, at a version or a time, and their traces: \`GET /${aggregate.context}/${aggregate.name}/{id}/state\`.`])}export class ${name}StateClient extends StateClient<${state}, ${name}Event> {\n${constructor}\n}`,
  );
  module.imports.add(WOW_CLIENT, client.sort());
  module.imports.add('./aggregate.js', aggregateNames);
  importTypes(module, outputDir, files, [state]);
}
