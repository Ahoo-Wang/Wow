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

import type { Reference, Schema } from '@ahoo-wang/fetcher-openapi';
import type {
  MethodDeclarationStructure,
  OptionalKind,
  ParameterDeclarationStructure,
} from 'ts-morph';
import type {
  ApiClientModel,
  ApiMethodModel,
  BodyModel,
  ReturnModel,
} from '../analysis/model';
import {
  addImport,
  addImportBoundedContext,
  modelModuleSpecifier,
} from '../emit/imports';
import { addJSDoc } from '../emit/jsdoc';
import type { ModuleBuilder } from '../emit/moduleBuilder';
import { quoteStringLiteral } from '../naming/naming';
import type { TypeScope } from '../types/typeResolver';
import { resolveType } from '../types/typeResolver';
import type { MethodReturnType } from './decorators';
import {
  addApiMetadataCtor,
  addImportDecorator,
  addImportEventStream,
  addImportFetcher,
  createDecoratorClass,
  DEFAULT_RETURN_TYPE,
  FETCHER_MODULE_SPECIFIER,
  methodToDecorator,
  STREAM_RESULT_EXTRACTOR_METADATA,
  STRING_RETURN_TYPE,
} from './decorators';
import type { EmitTarget } from './target';

/** Resolves schemas to types in one client module, importing the models they use. */
interface ClientTypes {
  readonly module: ModuleBuilder;
  resolveType(schema: Schema | Reference): string;
}

/**
 * Writes the API client of a tag: a decorated class with a method per
 * operation.
 *
 * @param client - The client
 * @param target - Where it is written
 */
export function emitApiClient(
  client: ApiClientModel,
  target: EmitTarget,
): void {
  const module = target.modules.module(client.file);
  addImportFetcher(module);
  addImportDecorator(module);
  addImportEventStream(module);
  const apiClientClass = createDecoratorClass(client.className, module);
  addJSDoc(apiClientClass, [client.description]);
  let defaults: string | undefined;
  if (client.basePath) {
    addImportBoundedContext(
      module,
      target.outputDir,
      client.basePath.alias,
      client.basePath.constantName,
    );
    defaults = `basePath: ${client.basePath.constantName}`;
  }
  addApiMetadataCtor(apiClientClass, defaults);
  const types = clientTypes(client.className, module, target);
  for (const method of client.methods) {
    (apiClientClass.methods ??= []).push(apiMethod(method, types));
  }
}

/**
 * The types of a client module. The client imports every model it uses,
 * and no import takes the class's own name.
 */
function clientTypes(
  className: string,
  module: ModuleBuilder,
  target: EmitTarget,
): ClientTypes {
  const scope: TypeScope = {
    context: target.types,
    owner: { name: className },
    specifierOf: model => modelModuleSpecifier(module, target.outputDir, model),
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
 * The method of an operation. Its types resolve in document order - the
 * parameters, the body, the response - so a later reference sees the
 * aliases an earlier one took; its parameters are then ordered required
 * first, so a caller never passes `undefined` to reach a required one:
 *
 * ```typescript
 * search(id: string, q: string, body: Filter, page?: number,
 *        httpRequest?: ParameterRequest, attributes?: Record<string, unknown>)
 * ```
 *
 * `httpRequest` carries anything else a request may set: headers, a
 * timeout, a signal.
 */
function apiMethod(
  method: ApiMethodModel,
  types: ClientTypes,
): OptionalKind<MethodDeclarationStructure> {
  const required: OptionalKind<ParameterDeclarationStructure>[] = [];
  const optional: OptionalKind<ParameterDeclarationStructure>[] = [];
  for (const parameter of method.parameters) {
    (parameter.required ? required : optional).push({
      name: parameter.name,
      type: parameter.schema ? types.resolveType(parameter.schema) : 'string',
      hasQuestionToken: !parameter.required,
      decorators: [
        {
          name: parameter.location,
          arguments: [quoteStringLiteral(parameter.parameterName)],
        },
      ],
    });
  }
  if (method.body) {
    (method.body.required ? required : optional).push({
      name: method.body.name,
      type: bodyType(method.body, types),
      hasQuestionToken: !method.body.required,
      decorators: [{ name: 'body', arguments: [] }],
    });
  }
  const parameters = [
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
  const returnType = returnTypeOf(method.returns, types);
  const path = quoteStringLiteral(method.path);
  const declaration: OptionalKind<MethodDeclarationStructure> = {
    name: method.name,
    decorators: [
      {
        name: methodToDecorator(method.httpMethod),
        arguments: returnType.metadata ? [path, returnType.metadata] : [path],
      },
    ],
    parameters,
    returnType: returnType.type,
    statements: [
      `throw autoGeneratedError(${parameters.map(parameter => parameter.name).join(',')});`,
    ],
  };
  addJSDoc(declaration, [...method.docs]);
  return declaration;
}

/**
 * The type of a body. A JSON body leaves optional the properties its schema
 * does not require, as a command body does (`PartialBy<Item, 'id'>`), unless
 * its type is a union, an intersection or `any`.
 */
function bodyType(body: BodyModel, types: ClientTypes): string {
  const { content } = body;
  switch (content.kind) {
    case 'json': {
      const type = types.resolveType(content.schema);
      const optional = content.optionalFields;
      if (optional.length === 0 || /[|&]/.test(type) || type === 'any') {
        return type;
      }
      addImport(types.module, FETCHER_MODULE_SPECIFIER, ['PartialBy']);
      return `PartialBy<${type}, ${optional.map(quoteStringLiteral).join(' | ')}>`;
    }
    case 'formData':
      return 'FormData';
    case 'urlEncoded':
      return 'URLSearchParams';
    case 'text':
      return 'string';
    case 'binary':
      return 'BodyInit';
  }
}

/** The return type of a method, and the decorator metadata it needs. */
function returnTypeOf(
  returns: ReturnModel,
  types: ClientTypes,
): MethodReturnType {
  switch (returns.kind) {
    case 'json': {
      const type = types.resolveType(returns.schema);
      return returns.wildcard && type === 'string'
        ? STRING_RETURN_TYPE
        : { type: `Promise<${type}>` };
    }
    case 'eventStream': {
      if (!returns.items) {
        return {
          type: `Promise<JsonServerSentEventStream<any>>`,
          metadata: STREAM_RESULT_EXTRACTOR_METADATA,
        };
      }
      const typeName = types.resolveType(returns.items);
      const dataType = returns.serverSentEvent
        ? `${typeName}['data']`
        : typeName;
      return {
        type: `Promise<JsonServerSentEventStream<${dataType}>>`,
        metadata: STREAM_RESULT_EXTRACTOR_METADATA,
      };
    }
    case 'text':
      return STRING_RETURN_TYPE;
    case 'response':
      return DEFAULT_RETURN_TYPE;
  }
}
