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
import { ContentTypeValues } from '@ahoo-wang/fetcher';
import type {
  Operation,
  Reference,
  RequestBody,
  Schema,
  Tag,
} from '@ahoo-wang/fetcher-openapi';
import type {
  ClassDeclarationStructure,
  MethodDeclarationStructure,
  OptionalKind,
  ParameterDeclarationStructure,
} from 'ts-morph';
import { GeneratorError } from '../api/errors';
import type { GenerateContext, Generator } from '../generateContext';
import type { ModelInfo } from '../model';
import {
  resolveContextDeclarationName,
  resolveModelInfo,
  resolveReferenceModelInfo,
} from '../model';
import type { OperationEndpoint } from '../openapi/operations';
import {
  addImport,
  addImportBoundedContext,
  modelModuleSpecifier,
} from '../emit/imports';
import { addJSDoc } from '../emit/jsdoc';
import type { ModuleBuilder } from '../emit/moduleBuilder';
import {
  extractOkResponse,
  extractOperationEndpoints,
  extractOperations,
  extractParameters,
} from '../openapi/operations';
import { extractRequestBody, extractSchema } from '../openapi/components';
import {
  extractResponseEventStreamSchema,
  extractResponseJsonSchema,
  extractResponseWildcardSchema,
  findMediaType,
  hasTextResponse,
  isJsonContentType,
  isTextContentType,
} from '../openapi/responses';
import { isArray, resolveOptionalFields } from '../openapi/schemas';
import { isReference } from '../openapi/references';
import { quoteStringLiteral } from '../naming/naming';
import type { TypeScope } from '../types/typeResolver';
import { resolveType } from '../types/typeResolver';
import type { MethodReturnType } from './decorators';
import {
  addApiMetadataCtor,
  addImportDecorator,
  FETCHER_MODULE_SPECIFIER,
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

/** Resolves schemas to types in one client module, importing the models they use. */
interface ClientTypes {
  readonly module: ModuleBuilder;
  resolveType(schema: Schema | Reference): string;
}

/** Parameter names every generated method uses itself. */
const RESERVED_PARAMETER_NAMES = ['httpRequest', 'attributes'];

/** A method parameter, with the document's description for its doc comment. */
type MethodParameter = OptionalKind<ParameterDeclarationStructure> & {
  description?: string;
};

/**
 * Generator for creating TypeScript API client classes from OpenAPI specifications.
 * Generates client classes with proper decorators, type annotations, and method signatures.
 */
export class ApiClientGenerator implements Generator {
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
    this.context.logger.debug('Starting API client generation');
    const apiClientTags: Map<string, Tag> = this.resolveApiTags();
    this.context.logger.debug(
      `Resolved ${apiClientTags.size} API client tags: ${Array.from(apiClientTags.keys()).join(', ')}`,
    );

    const groupOperations = this.groupOperations(apiClientTags);
    this.context.logger.debug(
      `Grouped operations into ${groupOperations.size} tag groups`,
    );

    this.generateApiClients(apiClientTags, groupOperations);
    this.context.logger.debug('API client generation completed');
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
    this.context.logger.debug(
      `Generating ${groupOperations.size} API client classes`,
    );
    const claimed = new Map<string, string>();
    const tagNames = [...groupOperations.keys()].sort();
    tagNames.forEach((tagName, index) => {
      this.context.logger.debug(
        `[${index + 1}/${tagNames.length}] Generating API client for tag: ${tagName}`,
      );
      const tag = apiClientTags.get(tagName)!;
      const modelInfo = resolveModelInfo(tagName);
      let clientInfo = modelInfo;
      for (let suffix = 2; claimed.has(this.clientKey(clientInfo)); suffix++) {
        clientInfo = { ...modelInfo, name: `${modelInfo.name}${suffix}` };
      }
      if (clientInfo !== modelInfo) {
        this.context.logger.warn(
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
   * The module of the API client.
   * @param modelInfo - The model information for the client
   */
  private createApiClientFile(modelInfo: ModelInfo): ModuleBuilder {
    let filePath = modelInfo.path;
    if (this.context.currentContextAlias) {
      filePath = combineURLs(this.context.currentContextAlias, filePath);
    }
    filePath = combineURLs(filePath, `${modelInfo.name}ApiClient.ts`);
    this.context.logger.debug(`Creating API client file: ${filePath}`);
    return this.context.module(filePath);
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
    this.context.logger.debug(
      `Generating API client class: ${className} with ${operations.size} operations`,
    );
    const apiClientFile = this.createApiClientFile(modelInfo);
    addImportFetcher(apiClientFile);
    addImportDecorator(apiClientFile);
    addImportEventStream(apiClientFile);
    const apiClientClass = createDecoratorClass(className, apiClientFile);
    addJSDoc(apiClientClass, [tag.description]);
    addApiMetadataCtor(apiClientClass, this.apiMetadataDefaults(apiClientFile));
    const types = this.clientTypes(className, apiClientFile);
    const methods = new Map<string, string>();
    for (const operation of operations) {
      this.processOperation(tag, apiClientClass, types, operation, methods);
    }
    this.context.logger.debug(`Completed API client: ${className}`);
  }

  /**
   * The types of a client module. The client imports every model it uses,
   * and no import takes the class's own name.
   */
  private clientTypes(className: string, module: ModuleBuilder): ClientTypes {
    const scope: TypeScope = {
      context: this.context.types,
      owner: { name: className },
      specifierOf: model =>
        modelModuleSpecifier(module, this.context.outputDir, model),
      imports: module.imports,
    };
    return {
      module,
      resolveType(schema) {
        const resolved = resolveType(schema, scope);
        module.imports.apply(resolved.imports);
        return resolved.text;
      },
    };
  }

  /**
   * The defaults the client's constructor merges `apiMetadata` over: the
   * bounded context's base path, when the document names its context.
   */
  private apiMetadataDefaults(module: ModuleBuilder): string | undefined {
    const contextAlias = this.context.currentContextAlias;
    if (!contextAlias) return undefined;
    const declarationName = resolveContextDeclarationName(contextAlias);
    addImportBoundedContext(
      module,
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
   * Resolves the body an operation sends.
   *
   * - JSON (`application/json`, `+json`) → the schema's type; properties the
   *   schema does not require are optional, as in a command body:
   *   `PartialBy<Item, 'id'>`;
   * - `multipart/form-data` → `FormData`;
   * - `application/x-www-form-urlencoded` → `URLSearchParams`;
   * - `text/*` → `string`;
   * - anything else → whatever a fetch request accepts.
   *
   * @param types - Resolves schemas to types, importing the models they use
   * @param operation - The operation to resolve the body of
   * @returns The body's type and whether it is required, or undefined when
   * the operation sends none
   */
  private resolveRequestBody(
    types: ClientTypes,
    operation: Operation,
  ): { type: string; required: boolean } | undefined {
    if (!operation.requestBody) {
      return undefined;
    }
    const requestBody: RequestBody | undefined = isReference(
      operation.requestBody,
    )
      ? extractRequestBody(
          operation.requestBody,
          this.context.openAPI.components!,
        )
      : operation.requestBody;
    if (!requestBody) {
      return undefined;
    }
    const required = requestBody.required === true;
    const content = requestBody.content ?? {};
    const json = findMediaType(
      content,
      ContentTypeValues.APPLICATION_JSON,
      isJsonContentType,
    );
    if (json?.schema) {
      const type = types.resolveType(json.schema);
      const optional = resolveOptionalFields(
        json.schema,
        this.context.openAPI.components,
      );
      if (optional.length === 0 || /[|&]/.test(type) || type === 'any') {
        return { type, required };
      }
      addImport(types.module, FETCHER_MODULE_SPECIFIER, ['PartialBy']);
      return {
        type: `PartialBy<${type}, ${optional.map(quoteStringLiteral).join(' | ')}>`,
        required,
      };
    }
    if (findMediaType(content, 'multipart/form-data')) {
      return { type: 'FormData', required };
    }
    if (findMediaType(content, 'application/x-www-form-urlencoded')) {
      return { type: 'URLSearchParams', required };
    }
    if (Object.keys(content).some(isTextContentType)) {
      return { type: 'string', required };
    }
    return { type: 'BodyInit', required };
  }

  /**
   * Resolves the parameters of the method an operation generates.
   *
   * Every path, query and header parameter the document declares becomes a
   * typed parameter, named after it (`item-id` → `itemId`), and so does the
   * request body. Required ones come first, in document order - path, query,
   * header, then the body - and optional ones follow, so a caller never
   * passes `undefined` to reach a required one:
   *
   * ```typescript
   * search(id: string, q: string, body: Filter, page?: number,
   *        httpRequest?: ParameterRequest, attributes?: Record<string, unknown>)
   * ```
   *
   * `httpRequest` carries anything else a request may set: headers, a
   * timeout, a signal. Path parameters the bounded context's interceptor
   * fills are left out (see {@link GenerateContext.isIgnoreApiClientPathParameters}),
   * and cookie parameters, which the browser sends, are left out with a
   * warning.
   *
   * @param tag - The tag for parameter filtering
   * @param types - Resolves schemas to types, importing the models they use
   * @param operation - The operation to resolve parameters for
   * @returns Array of parameter declarations
   */
  private resolveParameters(
    tag: Tag,
    types: ClientTypes,
    endpoint: OperationEndpoint,
  ): MethodParameter[] {
    const operation = endpoint.operation;
    const used = new Set(RESERVED_PARAMETER_NAMES);
    const required: MethodParameter[] = [];
    const optional: MethodParameter[] = [];
    const declared = extractParameters(
      operation,
      this.context.openAPI.components ?? {},
    );
    for (const location of ['path', 'query', 'header'] as const) {
      for (const parameter of declared) {
        if (parameter.in !== location) continue;
        if (
          location === 'path' &&
          this.context.isIgnoreApiClientPathParameters(tag.name, parameter.name)
        ) {
          continue;
        }
        const isRequired = location === 'path' || parameter.required === true;
        (isRequired ? required : optional).push({
          name: uniqueParameterName(parameter.name, used),
          type: parameter.schema
            ? types.resolveType(parameter.schema)
            : 'string',
          hasQuestionToken: !isRequired,
          decorators: [
            {
              name: location,
              arguments: [quoteStringLiteral(parameter.name)],
            },
          ],
          description: parameter.description,
        });
      }
    }
    const cookies = declared.filter(parameter => parameter.in === 'cookie');
    if (cookies.length > 0) {
      this.context.logger.warn(
        `${endpoint.method.toUpperCase()} ${endpoint.path} leaves out its cookie parameter(s) ${cookies.map(cookie => cookie.name).join(', ')}: the browser sends cookies, and fetch cannot set them.`,
      );
    }
    const body = this.resolveRequestBody(types, operation);
    if (body) {
      (body.required ? required : optional).push({
        name: uniqueParameterName('body', used),
        type: body.type,
        hasQuestionToken: !body.required,
        decorators: [{ name: 'body', arguments: [] }],
      });
    }
    return [
      ...required,
      ...optional,
      {
        name: 'httpRequest',
        hasQuestionToken: true,
        type: 'ParameterRequest',
        decorators: [{ name: 'request', arguments: [] }],
      },
      {
        name: 'attributes',
        hasQuestionToken: true,
        type: 'Record<string, unknown>',
        decorators: [{ name: 'attribute', arguments: [] }],
      },
    ];
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
    types: ClientTypes,
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
    apiClientClass: ClassDeclarationStructure,
    types: ClientTypes,
    operation: OperationEndpoint,
    methods: Map<string, string>,
  ) {
    this.context.logger.debug(
      `Processing operation: ${operation.operation.operationId} (${operation.method} ${operation.path})`,
    );
    const methodName = this.getMethodName(
      tag,
      apiClientClass.name!,
      operation,
      methods,
    );
    const parameters = this.resolveParameters(tag, types, operation);
    const returnType = this.resolveReturnType(types, operation.operation);
    const path = quoteStringLiteral(operation.path);
    const methodDecorator = {
      name: methodToDecorator(operation.method),
      arguments: returnType.metadata ? [path, returnType.metadata] : [path],
    };
    const methodDeclaration: OptionalKind<MethodDeclarationStructure> = {
      name: methodName,
      decorators: [methodDecorator],
      parameters: parameters.map(parameter => {
        const declaration = { ...parameter };
        delete declaration.description;
        return declaration;
      }),
      returnType: returnType.type,
      statements: [
        `throw autoGeneratedError(${parameters.map(parameter => parameter.name).join(',')});`,
      ],
    };
    (apiClientClass.methods ??= []).push(methodDeclaration);
    addJSDoc(methodDeclaration, [
      operation.operation.summary,
      operation.operation.description,
      `- operationId: \`${operation.operation.operationId}\``,
      `- path: \`${operation.path}\``,
      ...parameters
        .filter(parameter => parameter.description)
        .map(
          parameter =>
            `@param ${parameter.name} - ${parameter.description!.replace(/\s*\n\s*/g, ' ')}`,
        ),
    ]);
    this.context.logger.debug(`Operation method generated: ${methodName}`);
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
        this.context.logger.warn(
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
        this.context.logger.debug(
          `Skipping ${label}: its tags ${operationTags.join(', ')} include a Wow aggregate or system tag.`,
        );
        continue;
      }
      if (!endpoint.operation.operationId) {
        this.context.logger.warn(
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
        this.context.logger.debug(
          `Excluded tag: ${tag.name} (wow/Actuator/aggregate)`,
        );
      }
    });
    return apiClientTags;
  }
}
