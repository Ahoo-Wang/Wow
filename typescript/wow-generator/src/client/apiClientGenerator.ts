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
  Operation,
  Parameter,
  Reference,
  RequestBody,
  Schema,
  Tag,
} from '@ahoo-wang/fetcher-openapi';
import {
  addImportRefModel,
  camelCase,
  extractOkResponse,
  extractResponseJsonSchema,
  extractOperations,
  extractRequestBody,
  getOrCreateSourceFile,
  isPrimitive,
  isReference,
  MethodOperation,
  resolvePrimitiveType,
  extractResponseEventStreamSchema,
  extractSchema,
  isArray,
  extractResponseWildcardSchema,
  addJSDoc,
} from '../utils';
import {
  ModelInfo,
  resolveModelInfo,
  resolveReferenceModelInfo,
} from '../model';
import { combineURLs } from '@ahoo-wang/fetcher';
import {
  ClassDeclaration,
  OptionalKind,
  ParameterDeclarationStructure,
  SourceFile,
} from 'ts-morph';
import { methodToDecorator } from './utils';
import {
  addApiMetadataCtor,
  addImportDecorator,
  createDecoratorClass,
  STREAM_RESULT_EXTRACTOR_METADATA,
} from './decorators';

interface PathMethodOperation extends MethodOperation {
  path: string;
}

export class ApiClientGenerator extends BaseCodeGenerator {
  private defaultParameterRequestType = 'ParameterRequest';
  private defaultReturnType = { type: 'Promise<any>' };
  private currentContextAlias = this.openAPI.info['x-wow-context-alias'];
  private apiMetadataCtorInitializer = this.currentContextAlias ? `{basePath:'${this.currentContextAlias}'}` : undefined;

  constructor(context: GenerateContext) {
    super(context);
  }

  generate() {
    this.logger.info('Starting API client generation');
    const apiClientTags: Map<string, Tag> = this.resolveApiTags();
    this.logger.info(
      `Resolved ${apiClientTags.size} API client tags: ${Array.from(apiClientTags.keys()).join(', ')}`,
    );

    const groupOperations = this.groupOperations(apiClientTags);
    this.logger.info(
      `Grouped operations into ${groupOperations.size} tag groups`,
    );

    this.generateApiClients(apiClientTags, groupOperations);
    this.logger.success('API client generation completed');
  }

  private generateApiClients(
    apiClientTags: Map<string, Tag>,
    groupOperations: Map<string, Set<PathMethodOperation>>,
  ) {
    this.logger.info(`Generating ${groupOperations.size} API client classes`);
    let clientCount = 0;
    for (const [tagName, operations] of groupOperations) {
      clientCount++;
      this.logger.progressWithCount(
        clientCount,
        groupOperations.size,
        `Generating API client for tag: ${tagName}`,
      );
      const tag = apiClientTags.get(tagName)!;
      this.generateApiClient(tag, operations);
    }
  }

  private createApiClientFile(modelInfo: ModelInfo): SourceFile {
    let filePath = modelInfo.path;
    if (this.currentContextAlias) {
      filePath = combineURLs(this.currentContextAlias, filePath);
    }
    filePath = combineURLs(filePath, `${modelInfo.name}ApiClient.ts`);
    this.logger.info(`Creating API client file: ${filePath}`);
    return getOrCreateSourceFile(this.project, this.outputDir, filePath);
  }

  private generateApiClient(tag: Tag, operations: Set<PathMethodOperation>) {
    const modelInfo = resolveModelInfo(tag.name);
    this.logger.info(
      `Generating API client class: ${modelInfo.name}ApiClient with ${operations.size} operations`,
    );
    const apiClientFile = this.createApiClientFile(modelInfo);
    addImportDecorator(apiClientFile);
    const apiClientClass = createDecoratorClass(
      modelInfo.name + 'ApiClient',
      apiClientFile,
    );
    addJSDoc(apiClientClass, tag.description);
    addApiMetadataCtor(apiClientClass, this.apiMetadataCtorInitializer);
    this.logger.info(
      `Processing ${operations.size} operations for ${modelInfo.name}ApiClient`,
    );
    operations.forEach(operation => {
      this.processOperation(apiClientFile, apiClientClass, operation);
    });
    this.logger.success(`Completed API client: ${modelInfo.name}ApiClient`);
  }

  private getMethodName(
    apiClientClass: ClassDeclaration,
    operation: Operation,
  ): string {
    const parts = operation.operationId!.split('.');
    for (let i = parts.length - 1; i >= 0; i--) {
      const operationName = camelCase(parts.slice(i));
      if (apiClientClass.getMethod(operationName)) {
        continue;
      }
      return operationName;
    }
    return camelCase(parts);
  }

  private resolveRequestType(
    sourceFile: SourceFile,
    operation: Operation,
  ): string {
    if (!operation.requestBody) {
      this.logger.info(
        `No request body found for operation ${operation.operationId}, using default: ${this.defaultParameterRequestType}`,
      );
      return this.defaultParameterRequestType;
    }
    let requestBody: RequestBody | undefined;
    if (isReference(operation.requestBody)) {
      this.logger.info(
        `Extracting request body from reference for operation: ${operation.operationId}`,
      );
      requestBody = extractRequestBody(
        operation.requestBody,
        this.openAPI.components!,
      );
    } else {
      requestBody = operation.requestBody;
    }
    if (!requestBody) {
      this.logger.info(
        `Request body extraction failed for operation ${operation.operationId}, using default: ${this.defaultParameterRequestType}`,
      );
      return this.defaultParameterRequestType;
    }
    if (requestBody.content['multipart/form-data']) {
      this.logger.info(
        `Detected multipart/form-data content for operation ${operation.operationId}, using ParameterRequest<FormData>`,
      );
      return 'ParameterRequest<FormData>';
    }
    if (requestBody.content['application/json']) {
      const requestBodySchema = requestBody.content['application/json'].schema;
      if (isReference(requestBodySchema)) {
        const modelInfo = resolveReferenceModelInfo(requestBodySchema);
        this.logger.info(
          `Adding import for request body model: ${modelInfo.name} from ${modelInfo.path}`,
        );
        addImportRefModel(sourceFile, this.outputDir, modelInfo);
        const requestType = `ParameterRequest<${modelInfo.name}>`;
        this.logger.info(
          `Resolved request type for operation ${operation.operationId}: ${requestType}`,
        );
        return requestType;
      }
    }
    this.logger.info(
      `Using default request type for operation ${operation.operationId}: ${this.defaultParameterRequestType}`,
    );
    return this.defaultParameterRequestType;
  }

  private resolveParameters(
    sourceFile: SourceFile,
    operation: Operation,
  ): OptionalKind<ParameterDeclarationStructure>[] {
    if (!operation.parameters) {
      this.logger.info(
        `No parameters found for operation ${operation.operationId}`,
      );
      return [];
    }
    const pathParameters =
      (operation.parameters?.filter(parameter => {
        return (
          !isReference(parameter) &&
          parameter.in === 'path' &&
          parameter.name !== 'tenantId' &&
          parameter.name !== 'ownerId'
        );
      }) as Parameter[]) ?? [];
    this.logger.info(
      `Found ${pathParameters.length} path parameters for operation ${operation.operationId}`,
    );
    const parameters = pathParameters.map(parameter => {
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
    const requestType = this.resolveRequestType(sourceFile, operation);
    this.logger.info(`Adding httpRequest parameter: ${requestType}`);
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
    this.logger.info(`Adding attributes parameter: Record<string, any>`);
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

  private resolveSchemaReturnType(
    sourceFile: SourceFile,
    schema: Schema | Reference,
  ): string {
    if (isReference(schema)) {
      const modelInfo = resolveReferenceModelInfo(schema);
      this.logger.info(
        `Adding import for response model: ${modelInfo.name} from ${modelInfo.path}`,
      );
      addImportRefModel(sourceFile, this.outputDir, modelInfo);
      const returnType = `Promise<${modelInfo.name}>`;
      this.logger.info(`Resolved reference return type: ${returnType}`);
      return returnType;
    }
    if (!schema.type) {
      this.logger.info(
        `Schema has no type, using default return type: ${this.defaultReturnType.type}`,
      );
      return this.defaultReturnType.type;
    }
    if (isPrimitive(schema.type)) {
      const primitiveType = resolvePrimitiveType(schema.type);
      const returnType = `Promise<${primitiveType}>`;
      this.logger.info(`Resolved primitive return type: ${returnType}`);
      return returnType;
    }
    this.logger.info(
      `Using default return type: ${this.defaultReturnType.type}`,
    );
    return this.defaultReturnType.type;
  }

  private resolveReturnType(
    sourceFile: SourceFile,
    operation: Operation,
  ): { stream?: boolean; type: string } {
    const okResponse = extractOkResponse(operation);
    if (!okResponse) {
      this.logger.info(
        `No OK response found for operation ${operation.operationId}, using default return type: ${this.defaultReturnType.type}`,
      );
      return this.defaultReturnType;
    }
    const jsonSchema = extractResponseJsonSchema(okResponse);
    if (jsonSchema) {
      const returnType = this.resolveSchemaReturnType(sourceFile, jsonSchema);
      this.logger.info(
        `Resolved JSON response return type for operation ${operation.operationId}: ${returnType}`,
      );
      return {
        stream: false,
        type: returnType,
      };
    }
    const eventStreamSchema = extractResponseEventStreamSchema(okResponse);
    if (eventStreamSchema) {
      if (isReference(eventStreamSchema)) {
        const schema = extractSchema(
          eventStreamSchema,
          this.openAPI.components!,
        )!;
        if (isArray(schema) && isReference(schema.items)) {
          const modelInfo = resolveReferenceModelInfo(schema.items);
          this.logger.info(
            `Adding import for event stream model: ${modelInfo.name} from ${modelInfo.path}`,
          );
          addImportRefModel(sourceFile, this.outputDir, modelInfo);
          const returnType = `Promise<JsonServerSentEventStream<${modelInfo.name}['data']>>`;
          this.logger.info(
            `Resolved event stream return type for operation ${operation.operationId}: ${returnType}`,
          );
          return {
            stream: true,
            type: returnType,
          };
        }
      }
      const returnType = `Promise<JsonServerSentEventStream<any>>`;
      this.logger.info(
        `Resolved generic event stream return type for operation ${operation.operationId}: ${returnType}`,
      );
      return { stream: true, type: returnType };
    }
    const wildcardSchema = extractResponseWildcardSchema(okResponse);
    if (wildcardSchema) {
      const returnType = this.resolveSchemaReturnType(
        sourceFile,
        wildcardSchema,
      );
      this.logger.info(
        `Resolved wildcard response return type for operation ${operation.operationId}: ${returnType}`,
      );
      return { type: returnType };
    }
    this.logger.info(
      `Using default return type for operation ${operation.operationId}: ${this.defaultReturnType.type}`,
    );
    return this.defaultReturnType;
  }

  private processOperation(
    sourceFile: SourceFile,
    apiClientClass: ClassDeclaration,
    operation: PathMethodOperation,
  ) {
    this.logger.info(
      `Processing operation: ${operation.operation.operationId} (${operation.method} ${operation.path})`,
    );
    const methodName = this.getMethodName(apiClientClass, operation.operation);
    this.logger.info(`Generated method name: ${methodName}`);
    const parameters = this.resolveParameters(sourceFile, operation.operation);
    const returnType = this.resolveReturnType(sourceFile, operation.operation);
    const methodDecorator = returnType.stream
      ? {
        name: methodToDecorator(operation.method),
        arguments: [`'${operation.path}'`, STREAM_RESULT_EXTRACTOR_METADATA],
      }
      : {
        name: methodToDecorator(operation.method),
        arguments: [`'${operation.path}'`],
      };
    this.logger.info(
      `Creating method with ${parameters.length} parameters, return type: ${returnType.type}, stream: ${returnType.stream || false}`,
    );
    const methodDeclaration = apiClientClass.addMethod({
      name: methodName,
      decorators: [methodDecorator],
      parameters: parameters,
      returnType: returnType.type,
      statements: [
        `throw autoGeneratedError(${parameters.map(parameter => parameter.name).join(',')});`,
      ],
    });
    addJSDoc(
      methodDeclaration,
      operation.operation.summary,
      operation.operation.description,
    );
    this.logger.success(`Operation method generated: ${methodName}`);
  }

  private groupOperations(
    apiClientTags: Map<string, Tag>,
  ): Map<string, Set<PathMethodOperation>> {
    this.logger.info('Grouping operations by API client tags');
    const operations: Map<string, Set<PathMethodOperation>> = new Map();
    let totalOperations = 0;
    for (const [path, pathItem] of Object.entries(this.openAPI.paths)) {
      const methodOperations = extractOperations(pathItem).filter(
        methodOperation => {
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
        },
      );
      this.logger.info(
        `Path ${path}: found ${methodOperations.length} valid operations`,
      );
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
          totalOperations++;
        });
      }
    }
    this.logger.info(
      `Grouped ${totalOperations} operations into ${operations.size} tag groups`,
    );
    return operations;
  }

  private resolveApiTags(): Map<string, Tag> {
    this.logger.info('Resolving API client tags from OpenAPI specification');
    const apiClientTags: Map<string, Tag> = new Map<string, Tag>();
    const totalTags = this.openAPI.tags?.length || 0;
    let filteredTags = 0;
    this.openAPI.tags?.forEach(tag => {
      if (
        tag.name != 'wow' &&
        tag.name != 'Actuator' &&
        !this.isAggregateTag(tag)
      ) {
        apiClientTags.set(tag.name, tag);
        filteredTags++;
        this.logger.info(`Included API client tag: ${tag.name}`);
      } else {
        this.logger.info(`Excluded tag: ${tag.name} (wow/Actuator/aggregate)`);
      }
    });
    this.logger.info(
      `Resolved ${filteredTags} API client tags from ${totalTags} total tags`,
    );
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
