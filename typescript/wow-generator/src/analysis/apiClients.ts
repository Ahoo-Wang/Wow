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

import type { Operation, RequestBody, Tag } from '@ahoo-wang/fetcher-openapi';
import type { GeneratorConfiguration } from '../api/configuration';
import { GeneratorError } from '../api/errors';
import type { ModelInfo } from '../naming/modelInfo';
import { combinePaths } from '../naming/paths';
import { extractRequestBody, extractSchema } from '../openapi/components';
import type { OpenApiDocument } from '../openapi/document';
import type { OperationEndpoint } from '../openapi/operations';
import {
  extractOkResponse,
  extractParameters,
  inPathOrder,
} from '../openapi/operations';
import { isReference } from '../openapi/references';
import {
  APPLICATION_JSON,
  extractResponseEventStreamSchema,
  extractResponseJsonSchema,
  extractResponseWildcardSchema,
  findMediaType,
  hasTextResponse,
  isJsonContentType,
  isTextContentType,
} from '../openapi/responses';
import { isArray, resolveOptionalFields } from '../openapi/schemas';
import {
  IGNORED_API_CLIENT_TAGS,
  RESOURCE_ATTRIBUTION_PATH_PARAMETERS,
} from '../wow/conventions';
import type { WowModel } from '../wow/model';
import { isWowDocument } from '../wow/model';
import { resolveMethodName, uniqueParameterName } from './clientNames';
import type {
  ApiClientModel,
  ApiMethodModel,
  BodyModel,
  ContextReference,
  ParameterModel,
  ReturnModel,
} from './model';
import {
  resolveContextDeclarationName,
  resolveModelInfo,
  resolveReferenceModelInfo,
} from './modelInfo';

/** Parameter names every generated method uses itself. */
const RESERVED_PARAMETER_NAMES = ['httpRequest', 'attributes'];

/**
 * The API clients of a document: one per tag that is neither Wow's own, the
 * actuator's, nor an aggregate's, holding a method per operation.
 *
 * @param warnings - Receives a line for every operation left out and every
 * client renamed
 * @throws GeneratorError when two operations of a client name the same
 * method, or a configured or extension method name is not one
 */
export function analyzeApiClients(
  document: OpenApiDocument,
  wow: WowModel,
  config: GeneratorConfiguration,
  warnings: string[],
): ApiClientModel[] {
  const analysis = new ApiClientAnalysis(document, wow, config, warnings);
  return analysis.clients();
}

class ApiClientAnalysis {
  private readonly basePath?: ContextReference;
  private readonly wowDocument: boolean;

  constructor(
    private readonly document: OpenApiDocument,
    private readonly wow: WowModel,
    private readonly config: GeneratorConfiguration,
    private readonly warnings: string[],
  ) {
    this.wowDocument = isWowDocument(wow);
    if (wow.contextAlias) {
      this.basePath = {
        alias: wow.contextAlias,
        constantName: resolveContextDeclarationName(wow.contextAlias),
      };
    }
  }

  /**
   * One client per tag, sorted by tag name.
   *
   * Two tags can name the same class once normalised - `user-controller` and
   * `UserController`. The tag sorting first keeps the name, the others get a
   * numbered one, with a warning.
   */
  clients(): ApiClientModel[] {
    const tags = this.tags();
    const operations = this.groupOperations(tags);
    const claimed = new Map<string, string>();
    return [...operations.keys()].sort().map(tagName => {
      const modelInfo = resolveModelInfo(tagName);
      let clientInfo = modelInfo;
      for (let suffix = 2; claimed.has(clientKey(clientInfo)); suffix++) {
        clientInfo = { ...modelInfo, name: `${modelInfo.name}${suffix}` };
      }
      if (clientInfo !== modelInfo) {
        this.warnings.push(
          `Tags ${claimed.get(clientKey(modelInfo))} and ${tagName} both name the API client ${modelInfo.name}ApiClient; ${tagName} generates ${clientInfo.name}ApiClient.`,
        );
      }
      claimed.set(clientKey(clientInfo), tagName);
      return this.client(tags.get(tagName)!, clientInfo, [
        ...operations.get(tagName)!,
      ]);
    });
  }

  /** The client of a tag, under the name it was given. */
  private client(
    tag: Tag,
    modelInfo: ModelInfo,
    operations: readonly OperationEndpoint[],
  ): ApiClientModel {
    const className = `${modelInfo.name}ApiClient`;
    let file = modelInfo.path;
    if (this.wow.contextAlias) {
      file = combinePaths(this.wow.contextAlias, file);
    }
    file = combinePaths(file, apiClientFileName(className));
    const methods = new Map<string, string>();
    return {
      tagName: tag.name,
      className,
      file,
      description: tag.description,
      basePath: this.basePath,
      methods: operations.map(operation =>
        this.method(tag, className, operation, methods),
      ),
    };
  }

  /** The method an operation generates. */
  private method(
    tag: Tag,
    className: string,
    endpoint: OperationEndpoint,
    methods: Map<string, string>,
  ): ApiMethodModel {
    const { operation } = endpoint;
    const name = this.methodName(tag, className, endpoint, methods);
    const used = new Set(RESERVED_PARAMETER_NAMES);
    const parameters = this.parameters(tag, endpoint, used);
    const body = this.body(operation, used);
    // The method takes the required parameters first; its doc follows suit.
    const described = [
      ...parameters.filter(({ parameter }) => parameter.required),
      ...parameters.filter(({ parameter }) => !parameter.required),
    ].filter(({ description }) => description);
    return {
      name,
      httpMethod: endpoint.method,
      path: endpoint.path,
      parameters: parameters.map(({ parameter }) => parameter),
      body,
      returns: this.returns(operation),
      docs: [
        operation.summary,
        operation.description,
        `- operationId: \`${operation.operationId}\``,
        `- path: \`${endpoint.path}\``,
        ...described.map(
          ({ parameter, description }) =>
            `@param ${parameter.name} - ${description!.replace(/\s*\n\s*/g, ' ')}`,
        ),
      ],
    };
  }

  /**
   * Names the method an operation generates, failing when another operation
   * of the client already took the name: renaming either one to make room
   * would rename an existing method when an operation is added.
   */
  private methodName(
    tag: Tag,
    className: string,
    endpoint: OperationEndpoint,
    methods: Map<string, string>,
  ): string {
    const operationId = endpoint.operation.operationId!;
    const methodName = resolveMethodName(
      endpoint.operation,
      this.config.apiClients?.[tag.name]?.methodNames?.[operationId],
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
   * The path, query and header parameters of an operation, each named after
   * the document's name (`item-id` → `itemId`), in the order their types
   * resolve: the path parameters in the order the path holds them, then the
   * query and header parameters in the document's order. Path parameters the bounded context's interceptor fills are
   * left out (see {@link ignoresPathParameter}), and cookie parameters, which
   * the browser sends, are left out with a warning.
   */
  private parameters(
    tag: Tag,
    endpoint: OperationEndpoint,
    used: Set<string>,
  ): { parameter: ParameterModel; description?: string }[] {
    const declared = extractParameters(
      endpoint.operation,
      this.document.components ?? {},
    );
    const parameters: { parameter: ParameterModel; description?: string }[] =
      [];
    for (const location of ['path', 'query', 'header'] as const) {
      const located = declared.filter(parameter => parameter.in === location);
      for (const parameter of location === 'path'
        ? inPathOrder(endpoint.path, located)
        : located) {
        if (
          location === 'path' &&
          this.ignoresPathParameter(tag.name, parameter.name)
        ) {
          continue;
        }
        parameters.push({
          parameter: {
            name: uniqueParameterName(parameter.name, used),
            location,
            parameterName: parameter.name,
            schema: parameter.schema,
            required: location === 'path' || parameter.required === true,
          },
          description: parameter.description,
        });
      }
    }
    const cookies = declared.filter(parameter => parameter.in === 'cookie');
    if (cookies.length > 0) {
      this.warnings.push(
        `${endpoint.method.toUpperCase()} ${endpoint.path} leaves out its cookie parameter(s) ${cookies.map(cookie => cookie.name).join(', ')}: the browser sends cookies, and fetch cannot set them.`,
      );
    }
    return parameters;
  }

  /**
   * Tells whether an API client leaves a path parameter out: the ones the
   * configuration names for the tag, else in a Wow document the resource
   * attribution parameters Wow's interceptor fills.
   */
  private ignoresPathParameter(tagName: string, parameterName: string) {
    const ignored =
      this.config.apiClients?.[tagName]?.ignorePathParameters ??
      (this.wowDocument ? RESOURCE_ATTRIBUTION_PATH_PARAMETERS : []);
    return ignored.includes(parameterName);
  }

  /**
   * The body an operation sends, by its content: JSON (`application/json`,
   * `+json`), `multipart/form-data`, `application/x-www-form-urlencoded`,
   * `text/*`, or anything else.
   */
  private body(operation: Operation, used: Set<string>): BodyModel | undefined {
    if (!operation.requestBody) {
      return undefined;
    }
    const requestBody: RequestBody | undefined = isReference(
      operation.requestBody,
    )
      ? extractRequestBody(operation.requestBody, this.document.components!)
      : operation.requestBody;
    if (!requestBody) {
      return undefined;
    }
    const required = requestBody.required === true;
    const content = requestBody.content ?? {};
    const json = findMediaType(content, APPLICATION_JSON, isJsonContentType);
    const name = uniqueParameterName('body', used);
    if (json?.schema) {
      return {
        name,
        required,
        content: {
          kind: 'json',
          schema: json.schema,
          optionalFields: resolveOptionalFields(
            json.schema,
            this.document.components,
          ),
        },
      };
    }
    if (findMediaType(content, 'multipart/form-data')) {
      return { name, required, content: { kind: 'formData' } };
    }
    if (findMediaType(content, 'application/x-www-form-urlencoded')) {
      return { name, required, content: { kind: 'urlEncoded' } };
    }
    if (Object.keys(content).some(isTextContentType)) {
      return { name, required, content: { kind: 'text' } };
    }
    return { name, required, content: { kind: 'binary' } };
  }

  /**
   * What an operation returns, read from its success response: `200`, else
   * the lowest other 2xx.
   */
  private returns(operation: Operation): ReturnModel {
    const okResponse = extractOkResponse(operation, this.document.components);
    if (!okResponse) {
      return { kind: 'response' };
    }
    const responseJsonSchema = extractResponseJsonSchema(okResponse);
    const jsonSchema =
      responseJsonSchema || extractResponseWildcardSchema(okResponse);
    if (jsonSchema) {
      return {
        kind: 'json',
        schema: jsonSchema,
        wildcard: !responseJsonSchema,
      };
    }
    const eventStreamSchema = extractResponseEventStreamSchema(okResponse);
    if (eventStreamSchema) {
      if (isReference(eventStreamSchema)) {
        const schema = extractSchema(
          eventStreamSchema,
          this.document.components!,
        )!;
        if (isArray(schema) && isReference(schema.items)) {
          const modelInfo = resolveReferenceModelInfo(
            schema.items,
            this.document.components,
          );
          return {
            kind: 'eventStream',
            items: schema.items,
            serverSentEvent: modelInfo.name.includes('ServerSentEvent'),
          };
        }
      }
      return { kind: 'eventStream', serverSentEvent: false };
    }
    if (hasTextResponse(okResponse)) {
      return { kind: 'text' };
    }
    return { kind: 'response' };
  }

  /**
   * Groups operations by their tags.
   *
   * An operation needs an operationId, which names its method, and a tag,
   * which names its client; one without either is skipped with a warning.
   */
  private groupOperations(
    tags: Map<string, Tag>,
  ): Map<string, Set<OperationEndpoint>> {
    const operations = new Map<string, Set<OperationEndpoint>>();
    for (const endpoint of this.document.endpoints) {
      const label = `${endpoint.method.toUpperCase()} ${endpoint.path}`;
      const operationTags = endpoint.operation.tags ?? [];
      if (operationTags.length === 0) {
        this.warnings.push(
          `Skipping ${label}: it has no tag, and the tag names its API client.`,
        );
        continue;
      }
      const clientTags = operationTags.filter(tagName => tags.has(tagName));
      // Wow tags an aggregate's routes with the aggregate and with its own
      // tags; they belong to the command and query clients.
      if (clientTags.length < operationTags.length) continue;
      if (!endpoint.operation.operationId) {
        this.warnings.push(
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

  /**
   * The tags that generate API clients: the ones the operations use or the
   * document declares, but Wow's own, the actuator's and the aggregates'.
   */
  private tags(): Map<string, Tag> {
    const tags = new Map<string, Tag>();
    for (const { operation } of this.document.endpoints) {
      operation.tags?.forEach(tagName => {
        if (!this.ignoresTag(tagName) && !tags.has(tagName)) {
          tags.set(tagName, { name: tagName, description: '' });
        }
      });
    }
    this.document.openAPI.tags?.forEach(tag => {
      if (!this.ignoresTag(tag.name)) tags.set(tag.name, tag);
    });
    return tags;
  }

  private ignoresTag(tagName: string): boolean {
    return (
      IGNORED_API_CLIENT_TAGS.has(tagName) ||
      this.wow.aggregateTags.has(tagName)
    );
  }
}

/**
 * The file an API client is declared in: its class name with the first
 * letter lowered, `CartApiClient` → `cartApiClient.ts`, camelCase like every
 * other generated file. Client names differ by more than case (see
 * {@link clientKey}), so no two clients share a file, even on a file system
 * that ignores case.
 */
function apiClientFileName(className: string): string {
  return `${className.charAt(0).toLowerCase()}${className.slice(1)}.ts`;
}

function clientKey(modelInfo: ModelInfo): string {
  return `${modelInfo.path}/${modelInfo.name}`.toLowerCase();
}
