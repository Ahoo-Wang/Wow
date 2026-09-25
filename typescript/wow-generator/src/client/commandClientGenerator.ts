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

import type {
  ClassDeclarationStructure,
  EnumDeclarationStructure,
  MethodDeclarationStructure,
  OptionalKind,
  ParameterDeclarationStructure,
  TypeAliasDeclarationStructure,
  VariableStatementStructure,
} from 'ts-morph';
import { StructureKind, VariableDeclarationKind } from 'ts-morph';
import type {
  AggregateDefinition,
  CommandDefinition,
  TagAliasAggregate,
} from '../aggregate';
import type { GenerateContext, Generator } from '../generateContext';
import type { ModelInfo } from '../model';
import {
  IMPORT_WOW_PATH,
  resolveContextDeclarationName,
  resolveModelInfo,
} from '../model';
import {
  addImport,
  addImportBoundedContext,
  addImportRefModel,
} from '../emit/imports';
import { addJSDoc } from '../emit/jsdoc';
import type { ModuleBuilder } from '../emit/moduleBuilder';
import { membersWithTrailingComma } from '../emit/moduleBuilder';
import { camelCase, quoteStringLiteral } from '../naming/naming';
import { isEmptyObject, resolveOptionalFields } from '../openapi/schemas';
import { resolvePathParameterType } from '../openapi/operations';
import {
  addApiMetadataCtor,
  addImportDecorator,
  COMMAND_STREAM_ENDPOINT_METADATA,
  createDecoratorClass,
} from './decorators';
import {
  clientModulePath,
  methodToDecorator,
  resolveClassName,
  uniqueParameterName,
} from './utils';

/**
 * Generates TypeScript command client classes for aggregates.
 * Creates command clients that can send commands to aggregates.
 */
export class CommandClientGenerator implements Generator {
  private readonly commandEndpointPathsSuffix = 'CommandEndpointPaths';
  private readonly defaultCommandClientOptionsName =
    'DEFAULT_COMMAND_CLIENT_OPTIONS';

  /**
   * Creates a new CommandClientGenerator instance.
   * @param context - The generation context containing OpenAPI spec and project details
   */
  constructor(public readonly context: GenerateContext) {}

  /**
   * Generates command client classes for all aggregates.
   */
  generate(): void {
    const totalAggregates = Array.from(
      this.context.contextAggregates.values(),
    ).reduce((sum, set) => sum + set.size, 0);
    this.context.logger.debug('--- Generating Command Clients ---');
    this.context.logger.debug(
      `Generating command clients for ${totalAggregates} aggregates`,
    );
    let currentIndex = 0;
    for (const [, aggregates] of this.context.contextAggregates) {
      aggregates.forEach(aggregateDefinition => {
        currentIndex++;
        this.context.logger.debug(
          `[${currentIndex}/${totalAggregates}] Processing command client for aggregate: ${aggregateDefinition.aggregate.aggregateName}`,
        );
        this.processAggregate(aggregateDefinition);
      });
    }
    this.context.logger.debug('Command client generation completed');
  }

  /**
   * Processes and generates command client for an aggregate.
   * @param aggregate - The aggregate definition
   */
  processAggregate(aggregate: AggregateDefinition) {
    this.context.logger.debug(
      `Processing command client for aggregate: ${aggregate.aggregate.aggregateName} in context: ${aggregate.aggregate.contextAlias}`,
    );

    const commandClientFile = this.context.module(
      clientModulePath(aggregate.aggregate, 'commandClient'),
    );

    this.context.logger.debug(
      `Processing command endpoint paths for ${aggregate.commands.size} commands`,
    );

    const aggregateCommandEndpointPathsName = this.processCommandEndpointPaths(
      commandClientFile,
      aggregate,
    );
    this.processCommandTypes(commandClientFile, aggregate);
    this.context.logger.debug(
      `Creating default command client options: ${this.defaultCommandClientOptionsName}`,
    );
    const contextDeclarationName = resolveContextDeclarationName(
      aggregate.aggregate.contextAlias,
    );
    addImportBoundedContext(
      commandClientFile,
      this.context.outputDir,
      aggregate.aggregate.contextAlias,
      contextDeclarationName,
    );
    commandClientFile.add<VariableStatementStructure>({
      kind: StructureKind.VariableStatement,
      declarationKind: VariableDeclarationKind.Const,
      declarations: [
        {
          name: this.defaultCommandClientOptionsName,
          type: 'ApiMetadata',
          initializer: `{
        basePath: ${contextDeclarationName}
      }`,
        },
      ],
      isExported: false,
    });

    addImport(commandClientFile, IMPORT_WOW_PATH, [
      'CommandRequest',
      'CommandResult',
      'CommandResultEventStream',
      'CommandBody',
      COMMAND_STREAM_ENDPOINT_METADATA,
    ]);
    addImport(commandClientFile, '@ahoo-wang/fetcher', ['PartialBy']);

    this.context.logger.debug(
      `Adding imports from @ahoo-wang/fetcher-decorator: ApiMetadata types and decorators`,
    );
    addImportDecorator(commandClientFile);
    this.context.logger.debug(`Generating standard command client class`);
    this.processCommandClient(
      commandClientFile,
      aggregate,
      aggregateCommandEndpointPathsName,
    );

    this.context.logger.debug(`Generating stream command client class`);
    this.processStreamCommandClient(commandClientFile, aggregate);

    this.context.logger.debug(
      `Command client generation completed for aggregate: ${aggregate.aggregate.aggregateName}`,
    );
  }

  resolveAggregateCommandEndpointPathsName(
    aggregate: TagAliasAggregate,
  ): string {
    return resolveClassName(aggregate, this.commandEndpointPathsSuffix);
  }

  processCommandEndpointPaths(
    clientFile: ModuleBuilder,
    aggregateDefinition: AggregateDefinition,
  ): string {
    const aggregateCommandEndpointPathsName =
      this.resolveAggregateCommandEndpointPathsName(
        aggregateDefinition.aggregate,
      );
    this.context.logger.debug(
      `Creating command endpoint paths enum: ${aggregateCommandEndpointPathsName}`,
    );
    const members = this.endpointMemberNames(aggregateDefinition);
    clientFile.add<EnumDeclarationStructure>({
      kind: StructureKind.Enum,
      name: aggregateCommandEndpointPathsName,
      isExported: true,
      members: membersWithTrailingComma(
        [...aggregateDefinition.commands.values()].map(command => ({
          name: members.get(command)!,
          initializer: quoteStringLiteral(command.path),
        })),
      ),
    });
    this.context.logger.debug(
      `Command endpoint paths enum created with ${aggregateDefinition.commands.size} entries`,
    );
    return aggregateCommandEndpointPathsName;
  }

  private endpointMembers = new WeakMap<
    AggregateDefinition,
    Map<CommandDefinition, string>
  >();

  /**
   * Names each command's endpoint member: its name upper-cased
   * (`add_cart_item` → `ADD_CART_ITEM`), with characters no identifier may
   * hold replaced by `_` (`pay-order` → `PAY_ORDER`), a leading digit
   * prefixed with `_`, and a number appended to the second of two commands
   * that end up alike.
   */
  private endpointMemberNames(
    aggregateDefinition: AggregateDefinition,
  ): Map<CommandDefinition, string> {
    let names = this.endpointMembers.get(aggregateDefinition);
    if (names) return names;
    names = new Map();
    const used = new Set<string>();
    for (const command of aggregateDefinition.commands.values()) {
      let base = command.name.toUpperCase().replace(/[^\p{L}\p{N}_$]/gu, '_');
      if (!base || /^\p{N}/u.test(base)) base = `_${base}`;
      let name = base;
      for (let suffix = 2; used.has(name); suffix++) name = `${base}_${suffix}`;
      used.add(name);
      names.set(command, name);
    }
    this.endpointMembers.set(aggregateDefinition, names);
    return names;
  }

  /**
   * The method a command generates: its name camel-cased, as
   * `add_cart_item` → `addCartItem`.
   */
  private commandMethodName(definition: CommandDefinition): string {
    const name = camelCase(definition.name) || '_';
    return /^\p{N}/u.test(name) ? `_${name}` : name;
  }

  resolveCommandTypeName(definition: CommandDefinition): [ModelInfo, string] {
    const commandModelInfo = resolveModelInfo(definition.schema.key);
    return [commandModelInfo, commandModelInfo.name + 'Command'];
  }

  resolveCommandType(clientFile: ModuleBuilder, definition: CommandDefinition) {
    const [commandModelInfo, commandName] =
      this.resolveCommandTypeName(definition);
    if (commandModelInfo.path === IMPORT_WOW_PATH) {
      // Wow's own commands have their command types in wow-client.
      addImport(clientFile, IMPORT_WOW_PATH, [commandName]);
      return;
    }
    addImportRefModel(clientFile, this.context.outputDir, commandModelInfo);
    let commandType = `${commandModelInfo.name}`;
    const optionalFields = resolveOptionalFields(
      definition.schema.schema,
      this.context.openAPI.components,
    )
      .map(quoteStringLiteral)
      .join(' | ');
    if (optionalFields !== '') {
      commandType = `PartialBy<${commandType},${optionalFields}>`;
    }
    commandType = `CommandBody<${commandType}>`;
    clientFile.add<TypeAliasDeclarationStructure>({
      kind: StructureKind.TypeAlias,
      name: commandName,
      type: `${commandType}`,
      isExported: true,
    });
  }

  processCommandTypes(
    clientFile: ModuleBuilder,
    aggregateDefinition: AggregateDefinition,
  ) {
    aggregateDefinition.commands.forEach(command => {
      this.resolveCommandType(clientFile, command);
    });
  }

  getEndpointPath(
    aggregateDefinition: AggregateDefinition,
    aggregateCommandEndpointPathsName: string,
    command: CommandDefinition,
  ): string {
    return `${aggregateCommandEndpointPathsName}.${this.endpointMemberNames(aggregateDefinition).get(command)}`;
  }

  processCommandClient(
    clientFile: ModuleBuilder,
    aggregateDefinition: AggregateDefinition,
    aggregateCommandEndpointPathsName: string,
  ) {
    const commandClientName = resolveClassName(
      aggregateDefinition.aggregate,
      'CommandClient',
    );
    const commandClient = createDecoratorClass(
      commandClientName,
      clientFile,
      [],
      ['R = CommandResult'],
    );
    addApiMetadataCtor(
      commandClient,
      `...${this.defaultCommandClientOptionsName}`,
    );

    aggregateDefinition.commands.forEach(command => {
      this.processCommandMethod(
        aggregateDefinition,
        commandClient,
        command,
        aggregateCommandEndpointPathsName,
      );
    });
  }

  processStreamCommandClient(
    clientFile: ModuleBuilder,
    aggregateDefinition: AggregateDefinition,
  ) {
    const commandClientName = resolveClassName(
      aggregateDefinition.aggregate,
      'CommandClient',
    );
    const commandStreamClientName = resolveClassName(
      aggregateDefinition.aggregate,
      'StreamCommandClient',
    );

    // Inherits the constructor, which merges apiMetadata over the defaults.
    // wow-client's endpoint preset errors the stream with a WowError at the
    // server's error event, as CommandClient.sendAndWaitStream does.
    createDecoratorClass(
      commandStreamClientName,
      clientFile,
      [`''`, COMMAND_STREAM_ENDPOINT_METADATA],
      [],
      `${commandClientName}<CommandResultEventStream>`,
    );
  }

  private resolveParameters(
    definition: CommandDefinition,
  ): OptionalKind<ParameterDeclarationStructure>[] {
    const [commandModelInfo, commandName] =
      this.resolveCommandTypeName(definition);
    this.context.logger.debug(
      `Adding import for command model: ${commandModelInfo.name} from path: ${commandModelInfo.path}`,
    );

    const used = new Set(['commandRequest', 'attributes']);
    const parameters: OptionalKind<ParameterDeclarationStructure>[] =
      definition.pathParameters
        .filter(parameter => {
          return !this.context.isIgnoreCommandClientPathParameters(
            parameter.name,
          );
        })
        .map(parameter => ({
          name: uniqueParameterName(parameter.name, used),
          type: resolvePathParameterType(parameter),
          hasQuestionToken: false,
          decorators: [
            { name: 'path', arguments: [quoteStringLiteral(parameter.name)] },
          ],
        }));

    this.context.logger.debug(
      `Adding command request parameter: commandRequest (type: CommandRequest<${commandName}>)`,
    );
    parameters.push({
      name: 'commandRequest',
      hasQuestionToken: isEmptyObject(definition.schema.schema),
      type: `CommandRequest<${commandName}>`,
      decorators: [
        {
          name: 'request',
          arguments: [],
        },
      ],
    });

    parameters.push({
      name: 'attributes',
      hasQuestionToken: true,
      type: 'Record<string, unknown>',
      decorators: [
        {
          name: 'attribute',
          arguments: [],
        },
      ],
    });
    return parameters;
  }

  processCommandMethod(
    aggregate: AggregateDefinition,
    client: ClassDeclarationStructure,
    definition: CommandDefinition,
    aggregateCommandEndpointPathsName: string,
  ) {
    const methodName = this.commandMethodName(definition);
    this.context.logger.debug(
      `Generating command method: ${methodName} for command: ${definition.name}`,
    );
    this.context.logger.debug(
      `Command method details: HTTP ${definition.method}, path: ${definition.path}`,
    );
    const parameters = this.resolveParameters(definition);
    const methodDeclaration: OptionalKind<MethodDeclarationStructure> = {
      name: methodName,
      decorators: [
        {
          name: methodToDecorator(definition.method),
          arguments: [
            this.getEndpointPath(
              aggregate,
              aggregateCommandEndpointPathsName,
              definition,
            ),
          ],
        },
      ],
      parameters: parameters,
      returnType: 'Promise<R>',
      statements: `throw autoGeneratedError(${parameters.map(parameter => parameter.name).join(',')});`,
    };
    (client.methods ??= []).push(methodDeclaration);

    addJSDoc(methodDeclaration, [
      definition.summary,
      definition.description,
      `- operationId: \`${definition.operation.operationId}\``,
      `- path: \`${definition.path}\``,
    ]);

    this.context.logger.debug(`Command method generated: ${methodName}`);
  }
}
