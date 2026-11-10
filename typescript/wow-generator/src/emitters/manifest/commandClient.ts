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
import type {
  AggregateModel,
  CommandModel,
  ContextModel,
} from '../../manifest/model';
import { quoteStringLiteral, resolvePropertyName } from '../../naming/naming';
import {
  addStatement,
  doc,
  importTypes,
  type TypeFiles,
  WOW_CLIENT,
} from './generatedModule';

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
      command.idProperty === undefined
        ? undefined
        : `Its body names its aggregate: \`${command.idProperty}\`${command.idComputed ? ', which the service computes from the fields given' : ''}.`,
      creates,
      command.produces.length > 0
        ? `Produces ${command.produces.map(event => `\`${event}\``).join(', ')}.`
        : undefined,
    ],
    '  ',
  );
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
  const type = command.bodyType === 'unknown' ? 'object' : command.bodyType;
  // A computed id is the service's to compute from the fields: no input here.
  const body = command.idComputed
    ? `Omit<${type}, ${quoteStringLiteral(command.idProperty!)}>`
    : type;
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
export function emitCommandClient(
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
