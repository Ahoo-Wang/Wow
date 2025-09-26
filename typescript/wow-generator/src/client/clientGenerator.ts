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

import { GenerateContext } from '@/types.ts';
import { ClassDeclaration, Project, Scope, SourceFile } from 'ts-morph';
import { OpenAPI } from '@ahoo-wang/fetcher-openapi';
import {
  AggregateDefinition,
  CommandDefinition,
  TagAliasAggregate,
} from '@/aggregate';
import { IMPORT_WOW_PATH, pascalCase, resolveModelInfo } from '@/model';
import {
  addImport,
  addImportRefModel,
  getOrCreateSourceFile,
} from '@/utils/sourceFiles.ts';

/**
 * Generates TypeScript client classes for aggregates.
 * Creates query clients and command clients based on aggregate definitions.
 */
export class ClientGenerator implements GenerateContext {
  readonly project: Project;
  readonly openAPI: OpenAPI;
  readonly outputDir: string;
  readonly aggregates: Map<string, AggregateDefinition>;

  /**
   * Creates a new ClientGenerator instance.
   * @param context - The generation context containing OpenAPI spec and project details
   */
  constructor(context: GenerateContext) {
    this.project = context.project;
    this.openAPI = context.openAPI;
    this.outputDir = context.outputDir;
    this.aggregates = context.aggregates;
  }

  /**
   * Generates client classes for all aggregates.
   */
  generate(): void {
    this.aggregates.forEach(aggregate => {
      this.generateAggregate(aggregate);
    });
  }

  /**
   * Generates client classes for a specific aggregate.
   * @param aggregate - The aggregate definition to generate clients for
   */
  generateAggregate(aggregate: AggregateDefinition) {
    this.processQueryClient(aggregate);
    this.processCommandClient(aggregate);
  }

  /**
   * Creates or retrieves a source file for client generation.
   * @param aggregate - The aggregate metadata
   * @param fileName - The name of the client file
   * @returns The source file for the client
   */
  createClientFilePath(
    aggregate: TagAliasAggregate,
    fileName: string,
  ): SourceFile {
    const filePath = `${aggregate.contextAlias}/${aggregate.aggregateName}/${fileName}.ts`;
    return getOrCreateSourceFile(this.project, this.outputDir, filePath);
  }

  /**
   * Generates the client class name for an aggregate.
   * @param aggregate - The aggregate metadata
   * @param suffix - The suffix to append to the aggregate name
   * @returns The generated client class name
   */
  getClientName(aggregate: TagAliasAggregate, suffix: string): string {
    return `${pascalCase(aggregate.aggregateName)}${suffix}`;
  }

  /**
   * Processes and generates query client classes for an aggregate.
   * @param aggregate - The aggregate definition
   */
  processQueryClient(aggregate: AggregateDefinition) {
    const queryClientFile = this.createClientFilePath(
      aggregate.aggregate,
      'queryClient',
    );
    this.processSnapshotQueryClient(queryClientFile, aggregate);
    this.processEventStreamQueryClient(queryClientFile, aggregate);
  }

  /**
   * Processes and generates snapshot query client for an aggregate.
   * @param sourceFile - The source file to add the client to
   * @param aggregate - The aggregate definition
   */
  processSnapshotQueryClient(
    sourceFile: SourceFile,
    aggregate: AggregateDefinition,
  ) {
    addImport(sourceFile, IMPORT_WOW_PATH, ['SnapshotQueryClient']);
    const snapshotQueryClientName = this.getClientName(
      aggregate.aggregate,
      'SnapshotQueryClient',
    );
    const stateModelInfo = resolveModelInfo(aggregate.state.key);
    const fieldsModelInfo = resolveModelInfo(aggregate.fields.key);
    addImportRefModel(sourceFile, this.outputDir, stateModelInfo);
    addImportRefModel(sourceFile, this.outputDir, fieldsModelInfo);
    sourceFile.addClass({
      name: snapshotQueryClientName,
      isExported: true,
      extends: `SnapshotQueryClient<${stateModelInfo.name}, ${fieldsModelInfo.name}>`,
    });
  }

  /**
   * Processes and generates event stream query client for an aggregate.
   * @param sourceFile - The source file to add the client to
   * @param aggregate - The aggregate definition
   */
  processEventStreamQueryClient(
    sourceFile: SourceFile,
    aggregate: AggregateDefinition,
  ) {
    addImport(sourceFile, IMPORT_WOW_PATH, ['EventStreamQueryClient']);
    const snapshotQueryClientName = this.getClientName(
      aggregate.aggregate,
      'EventQueryClient',
    );
    const stateModelInfo = resolveModelInfo(aggregate.state.key);
    addImportRefModel(sourceFile, this.outputDir, stateModelInfo);
    sourceFile.addClass({
      name: snapshotQueryClientName,
      isExported: true,
      extends: `EventStreamQueryClient`,
    });
  }

  /**
   * Processes and generates command client for an aggregate.
   * @param aggregate - The aggregate definition
   */
  processCommandClient(aggregate: AggregateDefinition) {
    const commandClientFile = this.createClientFilePath(
      aggregate.aggregate,
      'commandClient',
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
    addImport(commandClientFile, '@ahoo-wang/fetcher-decorator', [
      'type ApiMetadata',
      'api',
      'post',
      'put',
      'del',
      'request',
      'attribute',
      'path',
    ]);
    const commandClientName = this.getClientName(
      aggregate.aggregate,
      'CommandClient',
    );
    const commandClient = commandClientFile.addClass({
      name: commandClientName,
      isExported: true,
      decorators: [
        {
          name: 'api',
          arguments: [],
        },
      ],
      implements: ['ApiMetadataCapable'],
    });
    commandClient.addConstructor({
      parameters: [
        {
          name: 'apiMetadata',
          type: 'ApiMetadata',
          scope: Scope.Public,
          isReadonly: true,
        },
      ],
    });
    aggregate.commands.forEach(command => {
      this.processCommandMethod(commandClientFile, commandClient, command);
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
  ) {
    const commandModelInfo = resolveModelInfo(definition.schema.key);
    addImportRefModel(sourceFile, this.outputDir, commandModelInfo);
    const pathParameters = definition.pathParameters.map(parameter => {
      return {
        name: parameter.name,
        type: 'string',
        decorators: [
          {
            name: 'path',
            arguments: [parameter.name],
          },
        ],
      };
    });
    client.addMethod({
      name: definition.name,
      decorators: [
        {
          name: definition.method,
          arguments: [`'${definition.path}'`],
        },
      ],
      parameters: [
        ...pathParameters,
        {
          name: 'commandRequest',
          type: `CommandRequest<${definition.name}>`,
          decorators: [
            {
              name: 'request',
              arguments: [],
            },
          ],
        },
        {
          name: 'attributes',
          type: 'Record<string, any>',
          decorators: [
            {
              name: 'attribute',
              arguments: [],
            },
          ],
        },
      ],
      returnType: 'Promise<CommandResult>',
    });
  }
}
