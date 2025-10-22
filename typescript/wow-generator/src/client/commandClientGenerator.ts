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

import { Tag } from '@ahoo-wang/fetcher-openapi';
import {
  ClassDeclaration, OptionalKind, ParameterDeclarationStructure, Scope,
  SourceFile,
  VariableDeclarationKind,
} from 'ts-morph';
import { AggregateDefinition, CommandDefinition } from '../aggregate';
import { GenerateContext, Generator } from '../generateContext';
import { IMPORT_WOW_PATH, resolveModelInfo } from '../model';
import {
  addImport,
  addImportRefModel,
  addJSDoc,
  camelCase, isEmptyObject, resolvePathParameterType,
} from '../utils';
import {
  addApiMetadataCtor,
  addImportDecorator, addImportEventStream, createDecoratorClass,
  STREAM_RESULT_EXTRACTOR_METADATA,
} from './decorators';
import { createClientFilePath, getClientName, methodToDecorator } from './utils';

/**
 * Generates TypeScript command client classes for aggregates.
 * Creates command clients that can send commands to aggregates.
 */
export class CommandClientGenerator implements Generator {
  private readonly commandEndpointPathsName = 'COMMAND_ENDPOINT_PATHS';
  private readonly defaultCommandClientOptionsName =
    'DEFAULT_COMMAND_CLIENT_OPTIONS';

  /**
   * Creates a new CommandClientGenerator instance.
   * @param context - The generation context containing OpenAPI spec and project details
   */
  constructor(public readonly context: GenerateContext) {
  }

  /**
   * Generates command client classes for all aggregates.
   */
  generate(): void {
    const totalAggregates = Array.from(this.context.contextAggregates.values()).reduce(
      (sum, set) => sum + set.size,
      0,
    );
    this.context.logger.info('--- Generating Command Clients ---');
    this.context.logger.progress(
      `Generating command clients for ${totalAggregates} aggregates`,
    );
    let currentIndex = 0;
    for (const [, aggregates] of this.context.contextAggregates) {
      aggregates.forEach(aggregateDefinition => {
        currentIndex++;
        this.context.logger.progressWithCount(
          currentIndex,
          totalAggregates,
          `Processing command client for aggregate: ${aggregateDefinition.aggregate.aggregateName}`,
        );
        this.processAggregate(aggregateDefinition);
      });
    }
    this.context.logger.success('Command client generation completed');
  }

  /**
   * Processes and generates command client for an aggregate.
   * @param aggregate - The aggregate definition
   */
  processAggregate(aggregate: AggregateDefinition) {
    this.context.logger.info(
      `Processing command client for aggregate: ${aggregate.aggregate.aggregateName} in context: ${aggregate.aggregate.contextAlias}`,
    );

    const commandClientFile = createClientFilePath(
      this.context.project,
      this.context.outputDir,
      aggregate.aggregate,
      'commandClient',
    );

    this.context.logger.info(
      `Processing command endpoint paths for ${aggregate.commands.size} commands`,
    );
    this.processCommandEndpointPaths(commandClientFile, aggregate);

    this.context.logger.info(
      `Creating default command client options: ${this.defaultCommandClientOptionsName}`,
    );
    commandClientFile.addVariableStatement({
      declarationKind: VariableDeclarationKind.Const,
      declarations: [
        {
          name: this.defaultCommandClientOptionsName,
          type: 'ApiMetadata',
          initializer: `{
        basePath: '${aggregate.aggregate.contextAlias}'
      }`,
        },
      ],
      isExported: false,
    });

    this.context.logger.info(
      `Adding imports from ${IMPORT_WOW_PATH}: CommandRequest, CommandResult, CommandResultEventStream, DeleteAggregate, RecoverAggregate`,
    );
    commandClientFile.addImportDeclaration({
      moduleSpecifier: IMPORT_WOW_PATH,
      namedImports: [
        'CommandRequest',
        'CommandResult',
        'CommandResultEventStream',
        'DeleteAggregate',
        'RecoverAggregate',
      ],
      isTypeOnly: true,
    });

    this.context.logger.info(
      `Adding import from @ahoo-wang/fetcher-eventstream: JsonEventStreamResultExtractor`,
    );
    addImportEventStream(commandClientFile);

    this.context.logger.info(
      `Adding import from @ahoo-wang/fetcher: ContentTypeValues`,
    );
    addImport(commandClientFile, '@ahoo-wang/fetcher', ['ContentTypeValues']);

    this.context.logger.info(
      `Adding imports from @ahoo-wang/fetcher-decorator: ApiMetadata types and decorators`,
    );
    addImportDecorator(commandClientFile);
    this.context.logger.info(`Generating standard command client class`);
    this.processCommandClient(commandClientFile, aggregate);

    this.context.logger.info(`Generating stream command client class`);
    this.processStreamCommandClient(commandClientFile, aggregate);

    this.context.logger.success(
      `Command client generation completed for aggregate: ${aggregate.aggregate.aggregateName}`,
    );
  }

  processCommandEndpointPaths(
    clientFile: SourceFile,
    aggregateDefinition: AggregateDefinition,
  ) {
    this.context.logger.info(
      `Creating command endpoint paths enum: ${this.commandEndpointPathsName}`,
    );
    const enumDeclaration = clientFile.addEnum({
      name: this.commandEndpointPathsName,
    });
    aggregateDefinition.commands.forEach(command => {
      this.context.logger.info(
        `Adding command endpoint: ${command.name.toUpperCase()} = '${command.path}'`,
      );
      enumDeclaration.addMember({
        name: command.name.toUpperCase(),
        initializer: `'${command.path}'`,
      });
    });
    this.context.logger.success(
      `Command endpoint paths enum created with ${aggregateDefinition.commands.size} entries`,
    );
  }

  getEndpointPath(command: CommandDefinition): string {
    return `${this.commandEndpointPathsName}.${command.name.toUpperCase()}`;
  }

  processCommandClient(
    clientFile: SourceFile,
    aggregateDefinition: AggregateDefinition,
  ) {
    const commandClientName = getClientName(
      aggregateDefinition.aggregate,
      'CommandClient',
    );
    const commandClient = createDecoratorClass(commandClientName, clientFile, [], ['R = CommandResult']);
    addApiMetadataCtor(commandClient, this.defaultCommandClientOptionsName);

    aggregateDefinition.commands.forEach(command => {
      this.processCommandMethod(aggregateDefinition, clientFile, commandClient, command);
    });
  }

  processStreamCommandClient(
    clientFile: SourceFile,
    aggregateDefinition: AggregateDefinition,
  ) {
    const commandClientName = getClientName(
      aggregateDefinition.aggregate,
      'CommandClient',
    );
    const commandStreamClientName = getClientName(
      aggregateDefinition.aggregate,
      'StreamCommandClient',
    );

    const streamCommandClient = createDecoratorClass(commandStreamClientName, clientFile, [
      `''`,
      STREAM_RESULT_EXTRACTOR_METADATA,
    ]);
    streamCommandClient.setExtends(`${commandClientName}<CommandResultEventStream>`);
    streamCommandClient.addConstructor({
      parameters: [
        {
          name: 'apiMetadata',
          type: 'ApiMetadata',
          initializer: this.defaultCommandClientOptionsName,
        } as OptionalKind<ParameterDeclarationStructure>,
      ],
      statements: `super(apiMetadata);`,
    });
  }


  private resolveParameters(
    tag: Tag,
    sourceFile: SourceFile,
    definition: CommandDefinition,
  ): OptionalKind<ParameterDeclarationStructure>[] {
    const commandModelInfo = resolveModelInfo(definition.schema.key);
    this.context.logger.info(
      `Adding import for command model: ${commandModelInfo.name} from path: ${commandModelInfo.path}`,
    );
    addImportRefModel(sourceFile, this.context.outputDir, commandModelInfo);
    const parameters = definition.pathParameters.filter(parameter => {
      return !this.context.isIgnoreCommandClientPathParameters(
        tag.name,
        parameter.name,
      );
    }).map(parameter => {
      const parameterType = resolvePathParameterType(parameter);
      this.context.logger.info(
        `Adding path parameter: ${parameter.name} (type: ${parameterType})`,
      );
      return {
        name: parameter.name,
        type: parameterType,
        hasQuestionToken: false,
        decorators: [
          {
            name: 'path',
            arguments: [`'${parameter.name}'`],
          },
        ],
      };
    });

    this.context.logger.info(
      `Adding command request parameter: commandRequest (type: CommandRequest<${commandModelInfo.name}>)`,
    );
    parameters.push({
      name: 'commandRequest',
      hasQuestionToken: isEmptyObject(definition.schema.schema),
      type: `CommandRequest<${commandModelInfo.name}>`,
      decorators: [
        {
          name: 'request',
          arguments: [],
        },
      ],
    });

    this.context.logger.info(
      `Adding attributes parameter: attributes (type: Record<string, any>)`,
    );
    parameters.push({
      name: 'attributes',
      hasQuestionToken: true,
      type: 'Record<string, any>',
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
    sourceFile: SourceFile,
    client: ClassDeclaration,
    definition: CommandDefinition,
  ) {
    this.context.logger.info(
      `Generating command method: ${camelCase(definition.name)} for command: ${definition.name}`,
    );
    this.context.logger.info(
      `Command method details: HTTP ${definition.method}, path: ${definition.path}`,
    );
    const parameters = this.resolveParameters(aggregate.aggregate.tag, sourceFile, definition);
    const methodDeclaration = client.addMethod({
      name: camelCase(definition.name),
      decorators: [
        {
          name: methodToDecorator(definition.method),
          arguments: [`${this.getEndpointPath(definition)}`],
        },
      ],
      parameters: parameters,
      returnType: 'Promise<R>',
      statements: `throw autoGeneratedError(${parameters.map(parameter => parameter.name).join(',')});`,
    });

    this.context.logger.info(
      `Adding JSDoc documentation for method: ${camelCase(definition.name)}`,
    );
    addJSDoc(methodDeclaration,
      [
        definition.summary,
        definition.description,
        `- operationId: \`${definition.operation.operationId}\``,
        `- path: \`${definition.path}\``,
      ]);

    this.context.logger.success(
      `Command method generated: ${camelCase(definition.name)}`,
    );
  }
}
