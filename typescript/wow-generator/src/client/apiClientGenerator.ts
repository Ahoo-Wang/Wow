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

import { combineURLs } from '@ahoo-wang/fetcher';
import {
  Operation,
  Reference,
  RequestBody,
  Schema,
  Tag,
} from '@ahoo-wang/fetcher-openapi';
import {
  ClassDeclaration,
  OptionalKind,
  ParameterDeclarationStructure,
  SourceFile,
} from 'ts-morph';
import { GenerateContext, Generator } from '../generateContext';
import {
  ModelInfo,
  resolveModelInfo,
  resolveReferenceModelInfo,
} from '../model';
import {
  addImportRefModel,
  addJSDoc,
  camelCase,
  extractOkResponse,
  extractOperations,
  extractPathParameters,
  extractRequestBody,
  extractResponseEventStreamSchema,
  extractResponseJsonSchema,
  extractResponseWildcardSchema,
  extractSchema,
  isArray,
  isPrimitive,
  isReference,
  MethodOperation,
  resolvePathParameterType,
  resolvePrimitiveType,
} from '../utils';
import {
  addApiMetadataCtor,
  addImportDecorator,
  createDecoratorClass,
  STREAM_RESULT_EXTRACTOR_METADATA,
} from './decorators';
import { methodToDecorator } from './utils';

/**
 * Interface extending MethodOperation with path information.
 */
interface PathMethodOperation extends MethodOperation {
  path: string;
}

/**
 * Generator for creating TypeScript API client classes from OpenAPI specifications.
 * Generates client classes with proper decorators, type annotations, and method signatures.
 */
export class ApiClientGenerator implements Generator {
  private defaultParameterRequestType = 'ParameterRequest';
  private defaultReturnType = { type: 'Promise<any>' };

  private readonly apiMetadataCtorInitializer: string | undefined;

  /**
   * Creates a new ApiClientGenerator instance.
   * @param context - The generation context containing OpenAPI spec and configuration
   */
  constructor(public readonly context: GenerateContext) {
    this.apiMetadataCtorInitializer = this.context.currentContextAlias
      ? `{basePath:'${this.context.currentContextAlias}'}`
      : undefined;
  }

  /**
   * Generates API client classes for all valid tags in the OpenAPI specification.
   * Processes tags, groups operations, and creates client classes with methods.
   */
  generate() {
    this.context.logger.info('Starting API client generation');
    const apiClientTags: Map<string, Tag> = this.resolveApiTags();
    this.context.logger.info(
      `Resolved ${apiClientTags.size} API client tags: ${Array.from(apiClientTags.keys()).join(', ')}`,
    );

    const groupOperations = this.groupOperations(apiClientTags);
    this.context.logger.info(
      `Grouped operations into ${groupOperations.size} tag groups`,
    );

    this.generateApiClients(apiClientTags, groupOperations);
    this.context.logger.success('API client generation completed');
  }

  /**
   * Generates API client classes for each tag group.
   * @param apiClientTags - Map of valid API client tags
   * @param groupOperations - Map of operations grouped by tag
   */
  private generateApiClients(
    apiClientTags: Map<string, Tag>,
    groupOperations: Map<string, Set<PathMethodOperation>>,
  ) {
    this.context.logger.info(
      `Generating ${groupOperations.size} API client classes`,
    );
    let clientCount = 0;
    for (const [tagName, operations] of groupOperations) {
      clientCount++;
      this.context.logger.progressWithCount(
        clientCount,
        groupOperations.size,
        `Generating API client for tag: ${tagName}`,
      );
      const tag = apiClientTags.get(tagName)!;
      this.generateApiClient(tag, operations);
    }
  }

  /**
   * Creates a new source file for the API client.
   * @param modelInfo - The model information for the client
   * @returns The created source file
   */
  private createApiClientFile(modelInfo: ModelInfo): SourceFile {
    let filePath = modelInfo.path;
    if (this.context.currentContextAlias) {
      filePath = combineURLs(this.context.currentContextAlias, filePath);
    }
    filePath = combineURLs(filePath, `${modelInfo.name}ApiClient.ts`);
    this.context.logger.info(`Creating API client file: ${filePath}`);
    return this.context.getOrCreateSourceFile(filePath);
  }

  /**
   * Generates a single API client class for the given tag and operations.
   * @param tag - The OpenAPI tag for the client
   * @param operations - Set of operations for this client
   */
  private generateApiClient(tag: Tag, operations: Set<PathMethodOperation>) {
    const modelInfo = resolveModelInfo(tag.name);
    this.context.logger.info(
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
    this.context.logger.info(
      `Processing ${operations.size} operations for ${modelInfo.name}ApiClient`,
    );
    operations.forEach(operation => {
      this.processOperation(tag, apiClientFile, apiClientClass, operation);
    });
    this.context.logger.success(
      `Completed API client: ${modelInfo.name}ApiClient`,
    );
  }

  /**
   * Generates a unique method name for the operation.
   * @param apiClientClass - The client class to check for existing methods
   * @param operation - The operation to generate a name for
   * @returns A unique camelCase method name
   */
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

  /**
   * Resolves the request type for an operation based on its request body.
   * @param sourceFile - The source file to add imports to
   * @param operation - The operation to resolve the request type for
   * @returns The resolved request type string
   */
  private resolveRequestType(
    sourceFile: SourceFile,
    operation: Operation,
  ): string {
    if (!operation.requestBody) {
      this.context.logger.info(
        `No request body found for operation ${operation.operationId}, using default: ${this.defaultParameterRequestType}`,
      );
      return this.defaultParameterRequestType;
    }
    let requestBody: RequestBody | undefined;
    if (isReference(operation.requestBody)) {
      this.context.logger.info(
        `Extracting request body from reference for operation: ${operation.operationId}`,
      );
      requestBody = extractRequestBody(
        operation.requestBody,
        this.context.openAPI.components!,
      );
    } else {
      requestBody = operation.requestBody;
    }
    if (!requestBody) {
      this.context.logger.info(
        `Request body extraction failed for operation ${operation.operationId}, using default: ${this.defaultParameterRequestType}`,
      );
      return this.defaultParameterRequestType;
    }
    if (requestBody.content['multipart/form-data']) {
      this.context.logger.info(
        `Detected multipart/form-data content for operation ${operation.operationId}, using ParameterRequest<FormData>`,
      );
      return 'ParameterRequest<FormData>';
    }
    if (requestBody.content['application/json']) {
      const requestBodySchema = requestBody.content['application/json'].schema;
      if (isReference(requestBodySchema)) {
        const modelInfo = resolveReferenceModelInfo(requestBodySchema);
        this.context.logger.info(
          `Adding import for request body model: ${modelInfo.name} from ${modelInfo.path}`,
        );
        addImportRefModel(sourceFile, this.context.outputDir, modelInfo);
        const requestType = `ParameterRequest<${modelInfo.name}>`;
        this.context.logger.info(
          `Resolved request type for operation ${operation.operationId}: ${requestType}`,
        );
        return requestType;
      }
    }
    this.context.logger.info(
      `Using default request type for operation ${operation.operationId}: ${this.defaultParameterRequestType}`,
    );
    return this.defaultParameterRequestType;
  }

  /**
   * Resolves method parameters for an operation.
   * @param tag - The tag for parameter filtering
   * @param sourceFile - The source file to add imports to
   * @param operation - The operation to resolve parameters for
   * @returns Array of parameter declarations
   */
  private resolveParameters(
    tag: Tag,
    sourceFile: SourceFile,
    operation: Operation,
  ): OptionalKind<ParameterDeclarationStructure>[] {
    const pathParameters = extractPathParameters(operation, this.context.openAPI.components!).filter(parameter => {
      return !this.context.isIgnoreApiClientPathParameters(
        tag.name,
        parameter.name,
      );
    });
    this.context.logger.info(
      `Found ${pathParameters.length} path parameters for operation ${operation.operationId}`,
    );
    const parameters = pathParameters.map(parameter => {
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
    const requestType = this.resolveRequestType(sourceFile, operation);
    this.context.logger.info(`Adding httpRequest parameter: ${requestType}`);
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
    this.context.logger.info(
      `Adding attributes parameter: Record<string, any>`,
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

  /**
   * Resolves the return type for a schema.
   * @param sourceFile - The source file to add imports to
   * @param schema - The schema to resolve the return type for
   * @returns The resolved return type string
   */
  private resolveSchemaReturnType(
    sourceFile: SourceFile,
    schema: Schema | Reference,
  ): string {
    if (isReference(schema)) {
      const modelInfo = resolveReferenceModelInfo(schema);
      this.context.logger.info(
        `Adding import for response model: ${modelInfo.name} from ${modelInfo.path}`,
      );
      addImportRefModel(sourceFile, this.context.outputDir, modelInfo);
      const returnType = `Promise<${modelInfo.name}>`;
      this.context.logger.info(`Resolved reference return type: ${returnType}`);
      return returnType;
    }
    if (!schema.type) {
      this.context.logger.info(
        `Schema has no type, using default return type: ${this.defaultReturnType.type}`,
      );
      return this.defaultReturnType.type;
    }
    if (isPrimitive(schema.type)) {
      const primitiveType = resolvePrimitiveType(schema.type);
      const returnType = `Promise<${primitiveType}>`;
      this.context.logger.info(`Resolved primitive return type: ${returnType}`);
      return returnType;
    }
    this.context.logger.info(
      `Using default return type: ${this.defaultReturnType.type}`,
    );
    return this.defaultReturnType.type;
  }

  /**
   * Resolves the return type for an operation based on its responses.
   * @param sourceFile - The source file to add imports to
   * @param operation - The operation to resolve the return type for
   * @returns Object containing type and optional stream flag
   */
  private resolveReturnType(
    sourceFile: SourceFile,
    operation: Operation,
  ): { stream?: boolean; type: string } {
    const okResponse = extractOkResponse(operation);
    if (!okResponse) {
      this.context.logger.info(
        `No OK response found for operation ${operation.operationId}, using default return type: ${this.defaultReturnType.type}`,
      );
      return this.defaultReturnType;
    }
    const jsonSchema = extractResponseJsonSchema(okResponse);
    if (jsonSchema) {
      const returnType = this.resolveSchemaReturnType(sourceFile, jsonSchema);
      this.context.logger.info(
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
          this.context.openAPI.components!,
        )!;
        if (isArray(schema) && isReference(schema.items)) {
          const modelInfo = resolveReferenceModelInfo(schema.items);
          this.context.logger.info(
            `Adding import for event stream model: ${modelInfo.name} from ${modelInfo.path}`,
          );
          addImportRefModel(sourceFile, this.context.outputDir, modelInfo);
          const dataType = modelInfo.name.includes('ServerSentEvent') ? `${modelInfo.name}['data']` : modelInfo.name;
          const returnType = `Promise<JsonServerSentEventStream<${dataType}>>`;
          this.context.logger.info(
            `Resolved event stream return type for operation ${operation.operationId}: ${returnType}`,
          );
          return {
            stream: true,
            type: returnType,
          };
        }
      }
      const returnType = `Promise<JsonServerSentEventStream<any>>`;
      this.context.logger.info(
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
      this.context.logger.info(
        `Resolved wildcard response return type for operation ${operation.operationId}: ${returnType}`,
      );
      return { type: returnType };
    }
    this.context.logger.info(
      `Using default return type for operation ${operation.operationId}: ${this.defaultReturnType.type}`,
    );
    return this.defaultReturnType;
  }

  /**
   * Processes a single operation and adds it as a method to the client class.
   * @param tag - The tag for parameter filtering
   * @param sourceFile - The source file containing the client
   * @param apiClientClass - The client class to add the method to
   * @param operation - The operation to process
   */
  private processOperation(
    tag: Tag,
    sourceFile: SourceFile,
    apiClientClass: ClassDeclaration,
    operation: PathMethodOperation,
  ) {
    this.context.logger.info(
      `Processing operation: ${operation.operation.operationId} (${operation.method} ${operation.path})`,
    );
    const methodName = this.getMethodName(apiClientClass, operation.operation);
    this.context.logger.info(`Generated method name: ${methodName}`);
    const parameters = this.resolveParameters(
      tag,
      sourceFile,
      operation.operation,
    );
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
    this.context.logger.info(
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
    this.context.logger.success(`Operation method generated: ${methodName}`);
  }

  /**
   * Groups operations by their tags for client generation.
   * @param apiClientTags - Map of valid API client tags
   * @returns Map of operations grouped by tag name
   */
  private groupOperations(
    apiClientTags: Map<string, Tag>,
  ): Map<string, Set<PathMethodOperation>> {
    this.context.logger.info('Grouping operations by API client tags');
    const operations: Map<string, Set<PathMethodOperation>> = new Map();
    let totalOperations = 0;
    for (const [path, pathItem] of Object.entries(this.context.openAPI.paths)) {
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
      this.context.logger.info(
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
    this.context.logger.info(
      `Grouped ${totalOperations} operations into ${operations.size} tag groups`,
    );
    return operations;
  }

  /**
   * Resolves valid API client tags from the OpenAPI specification.
   * Filters out system tags like 'wow' and 'Actuator' and aggregate tags.
   * @returns Map of valid API client tags
   */
  private resolveApiTags(): Map<string, Tag> {
    this.context.logger.info(
      'Resolving API client tags from OpenAPI specification',
    );
    const apiClientTags: Map<string, Tag> = new Map<string, Tag>();
    const totalTags = this.context.openAPI.tags?.length || 0;
    let filteredTags = 0;
    this.context.openAPI.tags?.forEach(tag => {
      if (
        tag.name != 'wow' &&
        tag.name != 'Actuator' &&
        !this.isAggregateTag(tag)
      ) {
        apiClientTags.set(tag.name, tag);
        filteredTags++;
        this.context.logger.info(`Included API client tag: ${tag.name}`);
      } else {
        this.context.logger.info(
          `Excluded tag: ${tag.name} (wow/Actuator/aggregate)`,
        );
      }
    });
    this.context.logger.info(
      `Resolved ${filteredTags} API client tags from ${totalTags} total tags`,
    );
    return apiClientTags;
  }

  private isAggregateTag(tag: Tag): boolean {
    for (const aggregates of this.context.contextAggregates.values()) {
      for (const aggregate of aggregates) {
        if (aggregate.aggregate.tag.name === tag.name) {
          return true;
        }
      }
    }
    return false;
  }
}
