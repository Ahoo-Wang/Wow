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
import type { Operation, RequestBody, Tag } from '@ahoo-wang/fetcher-openapi';
import type {
  ClassDeclaration,
  OptionalKind,
  ParameterDeclarationStructure,
  SourceFile,
} from 'ts-morph';
import { GeneratorError } from '../errors';
import type { GenerateContext, Generator } from '../generateContext';
import type { ModelInfo } from '../model';
import {
  resolveContextDeclarationName,
  resolveModelInfo,
  resolveReferenceModelInfo,
  TypeGenerator,
} from '../model';
import type { OperationEndpoint } from '../utils';
import {
  addImportBoundedContext,
  addJSDoc,
  extractOkResponse,
  extractOperationEndpoints,
  extractOperations,
  extractPathParameters,
  extractRequestBody,
  extractResponseEventStreamSchema,
  extractResponseJsonSchema,
  extractResponseWildcardSchema,
  extractSchema,
  hasTextResponse,
  isArray,
  isReference,
  quoteStringLiteral,
  resolvePathParameterType,
  warn,
} from '../utils';
import type { MethodReturnType } from './decorators';
import {
  addApiMetadataCtor,
  addImportDecorator,
  addImportEventStream,
  addImportFetcher,
  createDecoratorClass,
  DEFAULT_RETURN_TYPE,
  STREAM_RESULT_EXTRACTOR_METADATA,
  STRING_RETURN_TYPE,
} from './decorators';
import {
  methodToDecorator,
  resolveMethodName,
  uniqueParameterName,
} from './utils';

/** Parameter names every generated method uses itself. */
const RESERVED_PARAMETER_NAMES = ['httpRequest', 'attributes'];

/**
 * Generator for creating TypeScript API client classes from OpenAPI specifications.
 * Generates client classes with proper decorators, type annotations, and method signatures.
 */
export class ApiClientGenerator implements Generator {
  private defaultParameterRequestType = 'ParameterRequest';
  private defaultReturnType = DEFAULT_RETURN_TYPE;

  /**
   * Creates a new ApiClientGenerator instance.
   * @param context - The generation context containing OpenAPI spec and configuration
   */
  constructor(public readonly context: GenerateContext) {}

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
    this.context.logger.info('API client generation completed');
  }

  /**
   * Generates API client classes for each tag group.
   *
   * Two tags can name the same class once normalised - `user-controller` and
   * `UserController`. The tag sorting first keeps the name, the others get a
   * numbered one, with a warning.
   *
   * @param apiClientTags - Map of valid API client tags
   * @param groupOperations - Map of operations grouped by tag
   */
  private generateApiClients(
    apiClientTags: Map<string, Tag>,
    groupOperations: Map<string, Set<OperationEndpoint>>,
  ) {
    this.context.logger.info(
      `Generating ${groupOperations.size} API client classes`,
    );
    const claimed = new Map<string, string>();
    const tagNames = [...groupOperations.keys()].sort();
    tagNames.forEach((tagName, index) => {
      this.context.logger.progressWithCount(
        index + 1,
        tagNames.length,
        `Generating API client for tag: ${tagName}`,
      );
      const tag = apiClientTags.get(tagName)!;
      const modelInfo = resolveModelInfo(tagName);
      let clientInfo = modelInfo;
      for (let suffix = 2; claimed.has(this.clientKey(clientInfo)); suffix++) {
        clientInfo = { ...modelInfo, name: `${modelInfo.name}${suffix}` };
      }
      if (clientInfo !== modelInfo) {
        warn(
          this.context.logger,
          `Tags ${claimed.get(this.clientKey(modelInfo))} and ${tagName} both name the API client ${modelInfo.name}ApiClient; ${tagName} generates ${clientInfo.name}ApiClient.`,
        );
      }
      claimed.set(this.clientKey(clientInfo), tagName);
      this.generateApiClient(tag, clientInfo, groupOperations.get(tagName)!);
    });
  }

  private clientKey(modelInfo: ModelInfo): string {
    return `${modelInfo.path}/${modelInfo.name}`.toLowerCase();
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
   * @param modelInfo - The client's name and path
   * @param operations - Set of operations for this client
   */
  private generateApiClient(
    tag: Tag,
    modelInfo: ModelInfo,
    operations: Set<OperationEndpoint>,
  ) {
    const className = `${modelInfo.name}ApiClient`;
    this.context.logger.info(
      `Generating API client class: ${className} with ${operations.size} operations`,
    );
    const apiClientFile = this.createApiClientFile(modelInfo);
    addImportFetcher(apiClientFile);
    addImportDecorator(apiClientFile);
    addImportEventStream(apiClientFile);
    const apiClientClass = createDecoratorClass(className, apiClientFile);
    addJSDoc(apiClientClass, [tag.description]);
    addApiMetadataCtor(apiClientClass, this.apiMetadataDefaults(apiClientFile));
    const types = new TypeGenerator(
      { name: className, path: '\0client' },
      apiClientFile,
      { key: '', schema: {} },
      this.context.outputDir,
      this.context.openAPI.components,
    );
    const methods = new Map<string, string>();
    for (const operation of operations) {
      this.processOperation(tag, apiClientClass, types, operation, methods);
    }
    this.context.logger.info(`Completed API client: ${className}`);
  }

  /**
   * The defaults the client's constructor merges `apiMetadata` over: the
   * bounded context's base path, when the document names its context.
   */
  private apiMetadataDefaults(sourceFile: SourceFile): string | undefined {
    const contextAlias = this.context.currentContextAlias;
    if (!contextAlias) return undefined;
    const declarationName = resolveContextDeclarationName(contextAlias);
    addImportBoundedContext(
      sourceFile,
      this.context.outputDir,
      contextAlias,
      declarationName,
    );
    return `basePath: ${declarationName}`;
  }

  /**
   * Names the method an operation generates, failing when another operation
   * of the client already took the name: renaming either one to make room
   * would rename an existing method when an operation is added.
   */
  private getMethodName(
    tag: Tag,
    className: string,
    operation: OperationEndpoint,
    methods: Map<string, string>,
  ): string {
    const operationId = operation.operation.operationId!;
    const methodName = resolveMethodName(
      operation.operation,
      this.context.config.apiClients?.[tag.name]?.methodNames?.[operationId],
    )!;
    const taken = methods.get(methodName);
    if (taken !== undefined) {
      throw new GeneratorError(
        'specification',
        `Operations ${taken} and ${operationId} of tag ${tag.name} both generate the method ${className}.${methodName}(). Name one of them with apiClients["${tag.name}"].methodNames in the generator configuration, or with the ${'x-fetcher-method'} extension.`,
      );
    }
    methods.set(methodName, operationId);
    return methodName;
  }

  /**
   * Resolves the request type for an operation based on its request body.
   * @param types - Resolves schemas to types, importing the models they use
   * @param operation - The operation to resolve the request type for
   * @returns The resolved request type string
   */
  private resolveRequestType(
    types: TypeGenerator,
    operation: Operation,
  ): string {
    if (!operation.requestBody) {
      return this.defaultParameterRequestType;
    }
    let requestBody: RequestBody | undefined;
    if (isReference(operation.requestBody)) {
      requestBody = extractRequestBody(
        operation.requestBody,
        this.context.openAPI.components!,
      );
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
        return `ParameterRequest<${types.resolveType(requestBodySchema)}>`;
      }
    }
    return this.defaultParameterRequestType;
  }

  /**
   * Resolves method parameters for an operation.
   * @param tag - The tag for parameter filtering
   * @param types - Resolves schemas to types, importing the models they use
   * @param operation - The operation to resolve parameters for
   * @returns Array of parameter declarations
   */
  private resolveParameters(
    tag: Tag,
    types: TypeGenerator,
    operation: Operation,
  ): OptionalKind<ParameterDeclarationStructure>[] {
    const pathParameters = extractPathParameters(
      operation,
      this.context.openAPI.components!,
    ).filter(parameter => {
      return !this.context.isIgnoreApiClientPathParameters(
        tag.name,
        parameter.name,
      );
    });
    const used = new Set(RESERVED_PARAMETER_NAMES);
    const parameters: OptionalKind<ParameterDeclarationStructure>[] =
      pathParameters.map(parameter => ({
        name: uniqueParameterName(parameter.name, used),
        type: resolvePathParameterType(parameter),
        hasQuestionToken: false,
        decorators: [
          { name: 'path', arguments: [quoteStringLiteral(parameter.name)] },
        ],
      }));
    const requestType = this.resolveRequestType(types, operation);
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

  /**
   * Resolves the return type for an operation based on its success response:
   * `200`, else the lowest other 2xx.
   *
   * - JSON (`application/json`, `+json`, with or without parameters) → the
   *   schema's type;
   * - `text/*`, or a string schema under `*` + `/*` → `Promise<string>`;
   * - `text/event-stream` → a JSON server-sent event stream;
   * - anything else → the raw `Response`.
   *
   * @param types - Resolves schemas to types, importing the models they use
   * @param operation - The operation to resolve the return type for
   * @returns Object containing type and optional stream flag
   */
  private resolveReturnType(
    types: TypeGenerator,
    operation: Operation,
  ): MethodReturnType {
    const okResponse = extractOkResponse(
      operation,
      this.context.openAPI.components,
    );
    if (!okResponse) {
      return this.defaultReturnType;
    }
    const responseJsonSchema = extractResponseJsonSchema(okResponse);
    const jsonSchema =
      responseJsonSchema || extractResponseWildcardSchema(okResponse);
    if (jsonSchema) {
      const type = types.resolveType(jsonSchema);
      return !responseJsonSchema && type === 'string'
        ? STRING_RETURN_TYPE
        : { type: `Promise<${type}>` };
    }
    const eventStreamSchema = extractResponseEventStreamSchema(okResponse);
    if (eventStreamSchema) {
      if (isReference(eventStreamSchema)) {
        const schema = extractSchema(
          eventStreamSchema,
          this.context.openAPI.components!,
        )!;
        if (isArray(schema) && isReference(schema.items)) {
          const modelInfo = resolveReferenceModelInfo(
            schema.items,
            this.context.openAPI.components,
          );
          const typeName = types.resolveType(schema.items);
          const dataType = modelInfo.name.includes('ServerSentEvent')
            ? `${typeName}['data']`
            : typeName;
          return {
            type: `Promise<JsonServerSentEventStream<${dataType}>>`,
            metadata: STREAM_RESULT_EXTRACTOR_METADATA,
          };
        }
      }
      return {
        type: `Promise<JsonServerSentEventStream<any>>`,
        metadata: STREAM_RESULT_EXTRACTOR_METADATA,
      };
    }
    if (hasTextResponse(okResponse)) {
      return STRING_RETURN_TYPE;
    }
    return this.defaultReturnType;
  }

  /**
   * Processes a single operation and adds it as a method to the client class.
   * @param tag - The tag for parameter filtering
   * @param apiClientClass - The client class to add the method to
   * @param types - Resolves schemas to types, importing the models they use
   * @param operation - The operation to process
   * @param methods - Operation ids by the method names already taken
   */
  private processOperation(
    tag: Tag,
    apiClientClass: ClassDeclaration,
    types: TypeGenerator,
    operation: OperationEndpoint,
    methods: Map<string, string>,
  ) {
    this.context.logger.info(
      `Processing operation: ${operation.operation.operationId} (${operation.method} ${operation.path})`,
    );
    const methodName = this.getMethodName(
      tag,
      apiClientClass.getName()!,
      operation,
      methods,
    );
    const parameters = this.resolveParameters(tag, types, operation.operation);
    const returnType = this.resolveReturnType(types, operation.operation);
    const path = quoteStringLiteral(operation.path);
    const methodDecorator = {
      name: methodToDecorator(operation.method),
      arguments: returnType.metadata ? [path, returnType.metadata] : [path],
    };
    const methodDeclaration = apiClientClass.addMethod({
      name: methodName,
      decorators: [methodDecorator],
      parameters: parameters,
      returnType: returnType.type,
      statements: [
        `throw autoGeneratedError(${parameters.map(parameter => parameter.name).join(',')});`,
      ],
    });
    addJSDoc(methodDeclaration, [
      operation.operation.summary,
      operation.operation.description,
      `- operationId: \`${operation.operation.operationId}\``,
      `- path: \`${operation.path}\``,
    ]);
    this.context.logger.info(`Operation method generated: ${methodName}`);
  }

  /**
   * Groups operations by their tags for client generation.
   *
   * An operation needs an operationId, which names its method, and a tag,
   * which names its client; one without either is skipped with a warning.
   *
   * @param apiClientTags - Map of valid API client tags
   * @returns Map of operations grouped by tag name
   */
  private groupOperations(
    apiClientTags: Map<string, Tag>,
  ): Map<string, Set<OperationEndpoint>> {
    const operations: Map<string, Set<OperationEndpoint>> = new Map();
    const endpoints = extractOperationEndpoints(
      this.context.openAPI.paths,
      this.context.openAPI.components,
    );
    for (const endpoint of endpoints) {
      const label = `${endpoint.method.toUpperCase()} ${endpoint.path}`;
      const operationTags = endpoint.operation.tags ?? [];
      if (operationTags.length === 0) {
        warn(
          this.context.logger,
          `Skipping ${label}: it has no tag, and the tag names its API client.`,
        );
        continue;
      }
      const clientTags = operationTags.filter(tagName =>
        apiClientTags.has(tagName),
      );
      if (clientTags.length === 0) {
        continue;
      }
      if (clientTags.length < operationTags.length) {
        // Wow tags an aggregate's routes with the aggregate and with its
        // own tags; they belong to the command and query clients.
        this.context.logger.info(
          `Skipping ${label}: its tags ${operationTags.join(', ')} include a Wow aggregate or system tag.`,
        );
        continue;
      }
      if (!endpoint.operation.operationId) {
        warn(
          this.context.logger,
          `Skipping ${label}: it has no operationId, and the operationId names its method.`,
        );
        continue;
      }
      for (const tagName of clientTags) {
        if (!operations.has(tagName)) {
          operations.set(tagName, new Set());
        }
        operations.get(tagName)!.add(endpoint);
      }
    }
    return operations;
  }

  private shouldIgnoreTag(tagName: string): boolean {
    return (
      tagName === 'wow' ||
      tagName === 'Actuator' ||
      this.context.aggregateTags.has(tagName)
    );
  }

  /**
   * Resolves valid API client tags from the OpenAPI specification.
   * Filters out system tags like 'wow' and 'Actuator' and aggregate tags.
   * @returns Map of valid API client tags
   */
  private resolveApiTags(): Map<string, Tag> {
    const apiClientTags: Map<string, Tag> = new Map<string, Tag>();
    for (const pathItem of Object.values(this.context.openAPI.paths)) {
      extractOperations(pathItem).forEach(methodOperation => {
        methodOperation.operation.tags?.forEach(tagName => {
          if (!this.shouldIgnoreTag(tagName) && !apiClientTags.has(tagName)) {
            apiClientTags.set(tagName, {
              name: tagName,
              description: '',
            });
          }
        });
      });
    }
    this.context.openAPI.tags?.forEach(tag => {
      if (!this.shouldIgnoreTag(tag.name)) {
        apiClientTags.set(tag.name, tag);
      } else {
        this.context.logger.info(
          `Excluded tag: ${tag.name} (wow/Actuator/aggregate)`,
        );
      }
    });
    return apiClientTags;
  }
}
