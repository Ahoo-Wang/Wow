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
import { Operation, Parameter, Reference, RequestBody, Schema, Tag } from '@ahoo-wang/fetcher-openapi';
import {
  addImport, addImportRefModel,
  camelCase, extractOkResponse, extractResponseJsonSchema, extractOperations, extractRequestBody,
  getOrCreateSourceFile,
  isPrimitive,
  isReference,
  MethodOperation, resolvePrimitiveType, extractResponseEventStreamSchema, extractSchema, isArray,
  extractResponseWildcardSchema, addJSDoc,
} from '../utils';
import { ModelInfo, resolveModelInfo, resolveReferenceModelInfo } from '../model';
import { combineURLs } from '@ahoo-wang/fetcher';
import {
  ClassDeclaration,
  OptionalKind, ParameterDeclarationStructure,
  SourceFile,
} from 'ts-morph';
import { methodToDecorator } from './utils';
import { addApiMetadataCtor, addImportDecorator, createDecoratorClass } from './decorator';

interface PathMethodOperation extends MethodOperation {
  path: string;
}

export class ApiClientGenerator extends BaseCodeGenerator {
  private defaultParameterRequestType = 'ParameterRequest';
  private defaultReturnType = 'Promise<any>';

  constructor(context: GenerateContext) {
    super(context);
  }

  generate() {
    const apiClientTags: Map<string, Tag> = this.resolveApiTags();
    const groupOperations = this.groupOperations(apiClientTags);
    this.generateApiClients(apiClientTags, groupOperations);
  }

  private generateApiClients(apiClientTags: Map<string, Tag>, groupOperations: Map<string, Set<PathMethodOperation>>) {
    for (const [tagName, operations] of groupOperations) {
      const tag = apiClientTags.get(tagName)!;
      this.generateApiClient(tag, operations);
    }
  }

  private createApiClientFile(modelInfo: ModelInfo): SourceFile {
    const filePath = combineURLs(modelInfo.path, `${modelInfo.name}ApiClient.ts`);
    return getOrCreateSourceFile(this.project, this.outputDir, filePath);
  }

  private generateApiClient(tag: Tag, operations: Set<PathMethodOperation>) {
    const modelInfo = resolveModelInfo(tag.name);
    const apiClientFile = this.createApiClientFile(modelInfo);
    addImportDecorator(apiClientFile);
    const apiClientClass = createDecoratorClass(modelInfo.name + 'ApiClient', apiClientFile);
    addJSDoc(apiClientClass, tag.description);
    addApiMetadataCtor(apiClientClass);
    operations.forEach(operation => {
      this.processOperation(apiClientFile, apiClientClass, operation);
    });
  }

  private getMethodName(operation: Operation): string {
    const operationName = operation.operationId!.split('.').pop()!;
    return camelCase(operationName);
  }

  private resolveRequestType(sourceFile: SourceFile, operation: Operation): string {
    if (!operation.requestBody) {
      return this.defaultParameterRequestType;
    }
    let requestBody: RequestBody | undefined;
    if (isReference(operation.requestBody)) {
      requestBody = extractRequestBody(operation.requestBody, this.openAPI.components!);
    } else {
      requestBody = operation.requestBody;
    }
    if (!requestBody) {
      return this.defaultParameterRequestType;
    }
    if (requestBody.content['multipart/form-data']) {
      return 'ParameterRequest<FormData>';
    }
    if (requestBody.content['application/json']) {
      const requestBodySchema = requestBody.content['application/json'].schema;
      if (isReference(requestBodySchema)) {
        const modelInfo = resolveReferenceModelInfo(requestBodySchema);
        addImportRefModel(sourceFile, this.outputDir, modelInfo);
        return `ParameterRequest<${modelInfo.name}>`;
      }
    }
    return this.defaultParameterRequestType;
  }

  private resolveParameters(sourceFile: SourceFile, operation: Operation): OptionalKind<ParameterDeclarationStructure>[] {
    if (!operation.parameters) {
      return [];
    }
    const pathParameters = operation.parameters?.filter(parameter => {
      return !isReference(parameter) && parameter.in === 'path' && parameter.name !== 'tenantId' && parameter.name !== 'ownerId';
    }) as Parameter[] ?? [];
    const parameters = pathParameters.map(parameter => {
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
    const requestType = this.resolveRequestType(sourceFile, operation);
    parameters.push({
      name: 'httpRequest',
      hasQuestionToken: requestType === this.defaultParameterRequestType,
      type: `${requestType}`,
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

  private resolveSchemaReturnType(sourceFile: SourceFile, schema: Schema | Reference): string {
    if (isReference(schema)) {
      const modelInfo = resolveReferenceModelInfo(schema);
      addImportRefModel(sourceFile, this.outputDir, modelInfo);
      return `Promise<${modelInfo.name}>`;
    }
    if (!schema.type) {
      return this.defaultReturnType;
    }
    if (isPrimitive(schema.type)) {
      return `Promise<${resolvePrimitiveType(schema.type)}>`;
    }
    return this.defaultReturnType;
  }

  private resolveReturnType(sourceFile: SourceFile, operation: Operation): string {
    const okResponse = extractOkResponse(operation);
    if (!okResponse) {
      return this.defaultReturnType;
    }
    const jsonSchema = extractResponseJsonSchema(okResponse);
    if (jsonSchema) {
      return this.resolveSchemaReturnType(sourceFile, jsonSchema);
    }
    const eventStreamSchema = extractResponseEventStreamSchema(okResponse);
    if (eventStreamSchema) {
      if (isReference(eventStreamSchema)) {
        const schema = extractSchema(eventStreamSchema, this.openAPI.components!)!;
        if (isArray(schema) && isReference(schema.items)) {
          const modelInfo = resolveReferenceModelInfo(schema.items);
          addImportRefModel(sourceFile, this.outputDir, modelInfo);
          return `Promise<JsonServerSentEvent<${modelInfo.name}>>`;
        }
      }
      return `Promise<JsonServerSentEvent<any>>`;
    }
    const wildcardSchema = extractResponseWildcardSchema(okResponse);
    if (wildcardSchema) {
      return this.resolveSchemaReturnType(sourceFile, wildcardSchema);
    }
    return this.defaultReturnType;
  }

  private processOperation(sourceFile: SourceFile, apiClientClass: ClassDeclaration, operation: PathMethodOperation) {
    const methodName = this.getMethodName(operation.operation);
    const parameters = this.resolveParameters(sourceFile, operation.operation);
    const methodDeclaration = apiClientClass.addMethod({
      name: methodName,
      decorators: [
        {
          name: methodToDecorator(operation.method),
          arguments: [`'${operation.path}'`],
        },
      ],
      parameters: parameters,
      returnType: this.resolveReturnType(sourceFile, operation.operation),
      statements: [
        `throw autoGeneratedError(${parameters.map(parameter => parameter.name).join(',')});`,
      ],
    });
    addJSDoc(methodDeclaration, operation.operation.summary, operation.operation.description);
  }

  private groupOperations(apiClientTags: Map<string, Tag>): Map<string, Set<PathMethodOperation>> {
    const operations: Map<string, Set<PathMethodOperation>> = new Map();
    for (const [path, pathItem] of Object.entries(this.openAPI.paths)) {
      const methodOperations = extractOperations(pathItem).filter((methodOperation) => {
        if (!methodOperation.operation.operationId) {
          return false;
        }
        const operationTags = methodOperation.operation.tags;
        if (!operationTags || operationTags.length == 0) {
          return false;
        }
        return operationTags.every(tagName => {
          return apiClientTags.has(tagName);
        });
      });
      for (const methodOperation of methodOperations) {
        methodOperation.operation.tags!.forEach(tagName => {
          const pathMethodOperation: PathMethodOperation = {
            ...methodOperation,
            path,
          };
          if (!operations.has(tagName)) {
            operations.set(tagName, new Set());
          }
          operations.get(tagName)!.add(pathMethodOperation);
        });
      }
    }
    return operations;
  }

  private resolveApiTags(): Map<string, Tag> {
    const apiClientTags: Map<string, Tag> = new Map<string, Tag>();
    this.openAPI.tags?.forEach((tag) => {
      if (tag.name != 'wow' && tag.name != 'Actuator' && !this.isAggregateTag(tag)) {
        apiClientTags.set(tag.name, tag);
      }
    });
    return apiClientTags;
  }

  private isAggregateTag(tag: Tag): boolean {
    for (const aggregates of this.contextAggregates.values()) {
      for (const aggregate of aggregates) {
        if (aggregate.aggregate.tag.name === tag.name) {
          return true;
        }
      }
    }
    return false;
  }
}