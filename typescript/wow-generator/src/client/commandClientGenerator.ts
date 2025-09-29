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

import { BaseCodeGenerator } from '../baseCodeGenerator';
import { GenerateContext } from '../types';
import {
  ClassDeclaration,
  SourceFile,
  VariableDeclarationKind,
} from 'ts-morph';
import { AggregateDefinition, CommandDefinition } from '../aggregate';
import { createClientFilePath, getClientName, methodToDecorator } from './utils';
import { IMPORT_WOW_PATH, resolveModelInfo } from '../model';
import {
  addImport,
  addImportRefModel,
  addJSDoc,
  camelCase,
  isEmptyObject,
} from '../utils';
import {
  addApiMetadataCtor,
  addImportDecorator,
  createDecoratorClass,
  STREAM_RESULT_EXTRACTOR_METADATA,
} from './decorators';

/**
 * Generates TypeScript command client classes for aggregates.
 * Creates command clients that can send commands to aggregates.
 */
export class CommandClientGenerator extends BaseCodeGenerator {
  private readonly commandEndpointPathsName = 'COMMAND_ENDPOINT_PATHS';
  private readonly defaultCommandClientOptionsName =
    'DEFAULT_COMMAND_CLIENT_OPTIONS';

  /**
   * Creates a new CommandClientGenerator instance.
   * @param context - The generation context containing OpenAPI spec and project details
   */
  constructor(context: GenerateContext) {
    super(context);
  }

  /**
   * Generates command client classes for all aggregates.
   */
  generate(): void {
    const totalAggregates = Array.from(this.contextAggregates.values()).reduce(
      (sum, set) => sum + set.size,
      0,
    );
    this.logger.info('--- Generating Command Clients ---');
    this.logger.progress(
      `Generating command clients for ${totalAggregates} aggregates`,
    );
    let currentIndex = 0;
    for (const [, aggregates] of this.contextAggregates) {
      aggregates.forEach(aggregateDefinition => {
        currentIndex++;
        this.logger.progressWithCount(
          currentIndex,
          totalAggregates,
          `Processing command client for aggregate: ${aggregateDefinition.aggregate.aggregateName}`,
        );
        this.processAggregate(aggregateDefinition);
      });
    }
    this.logger.success('Command client generation completed');
  }

  /**
   * Processes and generates command client for an aggregate.
   * @param aggregate - The aggregate definition
   */
  processAggregate(aggregate: AggregateDefinition) {
    this.logger.info(
      `Processing command client for aggregate: ${aggregate.aggregate.aggregateName} in context: ${aggregate.aggregate.contextAlias}`,
    );

    const commandClientFile = createClientFilePath(
      this.project,
      this.outputDir,
      aggregate.aggregate,
      'commandClient',
    );

    this.logger.info(
      `Processing command endpoint paths for ${aggregate.commands.size} commands`,
    );
    this.processCommandEndpointPaths(commandClientFile, aggregate);

    this.logger.info(
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

    this.logger.info(
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

    this.logger.info(
      `Adding import from @ahoo-wang/fetcher-eventstream: JsonEventStreamResultExtractor`,
    );
    commandClientFile.addImportDeclaration({
      moduleSpecifier: '@ahoo-wang/fetcher-eventstream',
      namedImports: ['JsonEventStreamResultExtractor'],
    });

    this.logger.info(
      `Adding import from @ahoo-wang/fetcher: ContentTypeValues`,
    );
    addImport(commandClientFile, '@ahoo-wang/fetcher', ['ContentTypeValues']);

    this.logger.info(
      `Adding imports from @ahoo-wang/fetcher-decorator: ApiMetadata types and decorators`,
    );
    addImportDecorator(commandClientFile);
    this.logger.info(`Generating standard command client class`);
    this.processCommandClient(commandClientFile, aggregate);

    this.logger.info(`Generating stream command client class`);
    this.processCommandClient(commandClientFile, aggregate, true);

    this.logger.success(
      `Command client generation completed for aggregate: ${aggregate.aggregate.aggregateName}`,
    );
  }

  processCommandEndpointPaths(
    clientFile: SourceFile,
    aggregateDefinition: AggregateDefinition,
  ) {
    this.logger.info(
      `Creating command endpoint paths enum: ${this.commandEndpointPathsName}`,
    );
    const enumDeclaration = clientFile.addEnum({
      name: this.commandEndpointPathsName,
    });
    aggregateDefinition.commands.forEach(command => {
      this.logger.info(
        `Adding command endpoint: ${command.name.toUpperCase()} = '${command.path}'`,
      );
      enumDeclaration.addMember({
        name: command.name.toUpperCase(),
        initializer: `'${command.path}'`,
      });
    });
    this.logger.success(
      `Command endpoint paths enum created with ${aggregateDefinition.commands.size} entries`,
    );
  }

  getEndpointPath(command: CommandDefinition): string {
    return `${this.commandEndpointPathsName}.${command.name.toUpperCase()}`;
  }

  processCommandClient(
    clientFile: SourceFile,
    aggregateDefinition: AggregateDefinition,
    isStream: boolean = false,
  ) {
    let suffix = 'CommandClient';
    let apiDecoratorArgs: string[] = [];
    let returnType = `Promise<CommandResult>`;
    if (isStream) {
      suffix = 'Stream' + suffix;
      apiDecoratorArgs = [
        `''`,
        STREAM_RESULT_EXTRACTOR_METADATA,
      ];
      returnType = `Promise<CommandResultEventStream>`;
    }
    const commandClientName = getClientName(
      aggregateDefinition.aggregate,
      suffix,
    );
    const commandClient = createDecoratorClass(commandClientName, clientFile, apiDecoratorArgs);
    addApiMetadataCtor(commandClient, this.defaultCommandClientOptionsName);

    aggregateDefinition.commands.forEach(command => {
      this.processCommandMethod(clientFile, commandClient, command, returnType);
    });
  }

  /**
   * Processes and generates a command method for the command client.
   * @param sourceFile - The source file containing the client
   * @param client - The client class declaration
   * @param definition - The command definition
   */
  processCommandMethod(
    sourceFile: SourceFile,
    client: ClassDeclaration,
    definition: CommandDefinition,
    returnType: string,
  ) {
    const commandModelInfo = resolveModelInfo(definition.schema.key);
    this.logger.info(
      `Adding import for command model: ${commandModelInfo.name} from path: ${commandModelInfo.path}`,
    );
    addImportRefModel(sourceFile, this.outputDir, commandModelInfo);

    this.logger.info(
      `Generating command method: ${camelCase(definition.name)} for command: ${definition.name}`,
    );
    this.logger.info(
      `Command method details: HTTP ${definition.method}, path: ${definition.path}, return type: ${returnType}`,
    );

    const parameters = definition.pathParameters.map(parameter => {
      this.logger.info(
        `Adding path parameter: ${parameter.name} (type: string)`,
      );
      return {
        name: parameter.name,
        type: 'string',
        hasQuestionToken: false,
        decorators: [
          {
            name: 'path',
            arguments: [`'${parameter.name}'`],
          },
        ],
      };
    });

    this.logger.info(
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

    this.logger.info(
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

    const methodDeclaration = client.addMethod({
      name: camelCase(definition.name),
      decorators: [
        {
          name: methodToDecorator(definition.method),
          arguments: [`${this.getEndpointPath(definition)}`],
        },
      ],
      parameters: parameters,
      returnType: returnType,
      statements: [
        `throw autoGeneratedError(${parameters.map(parameter => parameter.name).join(',')});`,
      ],
    });

    if (definition.summary || definition.description) {
      this.logger.info(
        `Adding JSDoc documentation for method: ${camelCase(definition.name)}`,
      );
    }
    addJSDoc(methodDeclaration, definition.summary, definition.description);

    this.logger.success(
      `Command method generated: ${camelCase(definition.name)}`,
    );
  }
}
