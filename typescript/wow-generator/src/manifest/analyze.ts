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

import { camelCase, upperSnakeCase } from '../naming/naming';
import { compareNames } from '../naming/order';
import { type ManifestContext, type ManifestDocument } from './document';
import { aggregateModel, aggregateRoots } from './aggregates';
import type { ContextModel, ManifestModel, TypeModel } from './model';
import { contextTypeName, generatedNames } from './names';
import { SchemaTypes } from './schemaTypes';
import { serviceRouteModel } from './serviceRoutes';
import { TextNumbers } from './textNumbers';

/**
 * Decides what a Manifest generates: a `types.ts` per bounded context with
 * the types of the schemas its aggregates reach (a schema two contexts reach
 * is declared in the first), a `boundedContext.ts` with its error codes and
 * wait helpers, and for each aggregate its `aggregate.ts`,
 * `commandClient.ts` and `queryClient.ts`.
 *
 * @param manifest - The Manifest
 * @returns The model and the warnings it raised
 */
export function analyzeManifest(manifest: ManifestDocument): {
  model: ManifestModel;
  warnings: string[];
} {
  const types = new SchemaTypes(manifest.$defs ?? {});
  const contexts = manifest.contexts;
  const reach = new Map<string, string>();
  for (const context of contexts) {
    const roots = [
      ...(context.aggregates ?? []).flatMap(aggregate =>
        aggregateRoots(types, aggregate),
      ),
      ...(context.routes ?? []).flatMap(route => [
        ...types.references(route.request),
        ...types.references(route.response),
      ]),
    ];
    for (const key of types.closure(roots).sort(compareNames)) {
      if (!reach.has(key)) reach.set(key, context.name);
    }
  }
  const keys = [...reach.keys()].filter(
    key => types.schemaOf(key) && !types.inlined(key),
  );
  types.name([...keys].sort(compareNames), generatedNames(manifest));

  const typeModels: TypeModel[] = keys
    .map(key => typeModel(types, key, `${reach.get(key)}/types.ts`))
    .sort(
      (left, right) =>
        compareNames(left.file, right.file) ||
        compareNames(left.name, right.name),
    );

  const numbers = new TextNumbers(manifest.$defs ?? {}, key => ({
    name: `${camelCase(types.names.get(key) ?? key)}Numbers`,
    file: `${reach.get(key)}/textNumbers.ts`,
  }));
  const contextModels = contexts
    .map(context => contextModel(types, numbers, context))
    // Declared once every mark that refers to one is known.
    .map(context => ({
      ...context,
      textNumbers: numbers
        .declarations()
        .filter(
          declared => declared.file === `${context.directory}/textNumbers.ts`,
        ),
    }));
  return {
    model: {
      ...(manifest.service?.name ? { service: manifest.service.name } : {}),
      types: typeModels,
      contexts: contextModels,
    },
    warnings: [...new Set(types.warnings)],
  };
}

function typeModel(types: SchemaTypes, key: string, file: string): TypeModel {
  const schema = types.schemaOf(key)!;
  const name = types.names.get(key)!;
  const description =
    typeof schema.description === 'string' ? schema.description : undefined;
  const references = [...types.namedReferences(schema)]
    .map(ref => types.names.get(ref))
    .filter((ref): ref is string => ref !== undefined && ref !== name);
  const base = {
    key,
    name,
    file,
    ...(description === undefined ? {} : { description }),
    references: [...new Set(references)].sort(compareNames),
  };
  if (types.isInterface(schema)) {
    // A typed index signature would have to admit every property's type.
    const extra = types.additional(schema, key);
    return {
      ...base,
      properties: types.properties(schema, key),
      ...(extra === undefined ? {} : { type: 'unknown' }),
    };
  }
  return { ...base, type: types.type(schema, key) };
}

function contextModel(
  types: SchemaTypes,
  numbers: TextNumbers,
  context: ManifestContext,
): Omit<ContextModel, 'textNumbers'> {
  return {
    name: context.name,
    ...(context.description ? { description: context.description } : {}),
    typeName: contextTypeName(context),
    constantName: upperSnakeCase(context.name),
    directory: context.name,
    errors: (context.errors ?? []).map(error => ({
      code: error.code,
      status: error.status,
      ...(error.description ? { description: error.description } : {}),
    })),
    subscriptions: [
      ...new Set((context.subscriptions ?? []).map(s => s.name)),
    ].sort(compareNames),
    aggregates: (context.aggregates ?? []).map(aggregate =>
      aggregateModel(types, numbers, context, aggregate),
    ),
    routes: (context.routes ?? []).map(route =>
      serviceRouteModel(types, numbers, context, route),
    ),
  };
}
