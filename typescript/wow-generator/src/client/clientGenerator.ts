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
import { Project, SourceFile } from 'ts-morph';
import { OpenAPI } from '@ahoo-wang/fetcher-openapi';
import { AggregateDefinition, TagAliasAggregate } from '@/aggregate';
import { IMPORT_WOW_PATH, pascalCase, resolveModelInfo } from '@/model';
import { addImport, addImportRefModel, getOrCreateSourceFile } from '@/utils/sourceFiles.ts';

export class ClientGenerator implements GenerateContext {
  readonly project: Project;
  readonly openAPI: OpenAPI;
  readonly outputDir: string;
  readonly aggregates: Map<string, AggregateDefinition>;

  constructor(context: GenerateContext) {
    this.project = context.project;
    this.openAPI = context.openAPI;
    this.outputDir = context.outputDir;
    this.aggregates = context.aggregates;
  }

  generate(): void {
    this.aggregates.forEach((aggregate) => {
      this.generateAggregate(aggregate);
    });
  }

  generateAggregate(aggregate: AggregateDefinition) {
    this.processQueryClient(aggregate);
    this.processCommandClient(aggregate);
  }

  createClientFilePath(aggregate: TagAliasAggregate, fileName: string): SourceFile {
    const filePath = `${aggregate.contextAlias}/${aggregate.aggregateName}/${fileName}.ts`;
    return getOrCreateSourceFile(this.project, this.outputDir, filePath);
  }

  getClientName(aggregate: TagAliasAggregate, suffix: string): string {
    return `${pascalCase(aggregate.aggregateName)}${suffix}`;
  }

  processQueryClient(aggregate: AggregateDefinition) {
    const queryClientFile = this.createClientFilePath(aggregate.aggregate, 'queryClient');
    this.processSnapshotQueryClient(queryClientFile, aggregate);
    this.processEventStreamQueryClient(queryClientFile, aggregate);
  }

  processSnapshotQueryClient(sourceFile: SourceFile, aggregate: AggregateDefinition) {
    addImport(sourceFile, IMPORT_WOW_PATH, ['SnapshotQueryClient']);
    const snapshotQueryClientName = this.getClientName(aggregate.aggregate, 'SnapshotQueryClient');
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

  processEventStreamQueryClient(sourceFile: SourceFile, aggregate: AggregateDefinition) {
    addImport(sourceFile, IMPORT_WOW_PATH, ['EventStreamQueryClient']);
    const snapshotQueryClientName = this.getClientName(aggregate.aggregate, 'EventQueryClient');
    const stateModelInfo = resolveModelInfo(aggregate.state.key);
    addImportRefModel(sourceFile, this.outputDir, stateModelInfo);
    sourceFile.addClass({
      name: snapshotQueryClientName,
      isExported: true,
      extends: `EventStreamQueryClient`,
    });
  }

  processCommandClient(aggregate: AggregateDefinition) {
    const commandClientFile = this.createClientFilePath(aggregate.aggregate, 'commandClient');
    commandClientFile.addImportDeclaration({
      moduleSpecifier: IMPORT_WOW_PATH,
      namedImports: ['CommandRequest', 'CommandResult', 'CommandResultEventStream', 'DeleteAggregate', 'RecoverAggregate'],
      isTypeOnly: true,
    });
    addImport(commandClientFile, '@ahoo-wang/fetcher-decorator',
      ['type ApiMetadata', 'api', 'post', 'put', 'del', 'request', 'attribute', 'path']);
    const commandClientName = this.getClientName(aggregate.aggregate, 'CommandClient');
    commandClientFile.addClass({
      name: commandClientName,
      isExported: true,
      decorators: [{
        name: 'api',
      }],
    });
  }
}