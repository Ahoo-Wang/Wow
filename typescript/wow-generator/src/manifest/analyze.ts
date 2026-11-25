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

import { upperSnakeCase } from '../naming/naming';
import { compareNames } from '../naming/order';
import { type ManifestContext, type ManifestDocument } from './document';
import {
  aggregateInputs,
  aggregateModel,
  aggregateRoots,
  routingCommands,
} from './aggregates';
import type { ContextModel, ManifestModel, TypeModel } from './model';
import {
  checkGeneratedNames,
  contextTypeName,
  generatedNames,
  memberName,
} from './names';
import { type Position, SchemaTypes } from './schemaTypes';
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
  checkGeneratedNames(manifest);
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
        ...types.references(route.parameters),
      ]),
    ];
    for (const key of types.closure(roots).sort(compareNames)) {
      if (!reach.has(key)) reach.set(key, context.name);
    }
  }
  const keys = [...reach.keys()].filter(
    key => types.schemaOf(key) && !types.inlined(key),
  );
  const reserved = generatedNames(manifest);
  types.name([...keys].sort(compareNames), reserved);
  // A type a caller sends that holds a read-only property, at any depth, is
  // declared again as its input, which leaves that property optional.
  const declared = new Set(keys);
  const inputs = types
    .inputsOf(
      types.closure(contexts.flatMap(context => inputRoots(types, context))),
    )
    .filter(key => declared.has(key))
    .sort(compareNames);
  types.nameInputs(inputs, reserved);

  const typeModels: TypeModel[] = [
    ...keys.map(key => typeModel(types, key, `${reach.get(key)}/types.ts`)),
    ...inputs.map(key =>
      typeModel(types, key, `${reach.get(key)}/types.ts`, 'input'),
    ),
  ].sort(
    (left, right) =>
      compareNames(left.file, right.file) ||
      compareNames(left.name, right.name),
  );

  // Each recursive type's marks under a name of its own: two types whose
  // names differ only in case (`ABTree`, `Abtree`) take `aBTreeNumbers` and
  // `abtreeNumbers`, and a name taken still is numbered.
  const markNames = new Map<string, string>();
  const takenMarks = new Set<string>();
  const markName = (key: string): string => {
    const known = markNames.get(key);
    if (known !== undefined) return known;
    const base = `${memberName(types.names.get(key) ?? key)}Numbers`;
    let name = base;
    for (let index = 2; takenMarks.has(name); index += 1) {
      name = `${base}${index}`;
    }
    markNames.set(key, name);
    takenMarks.add(name);
    return name;
  };
  const numbers = new TextNumbers(manifest.$defs ?? {}, key => ({
    name: markName(key),
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

/** The keys a caller of `context` sends: its commands' and lifecycle bodies, its routes' requests and parameters. */
function inputRoots(types: SchemaTypes, context: ManifestContext): string[] {
  return [
    ...(context.aggregates ?? []).flatMap(aggregate =>
      aggregateInputs(types, aggregate),
    ),
    ...(context.routes ?? []).flatMap(route => [
      ...types.references(route.request),
      ...types.references(route.parameters),
    ]),
  ];
}

function typeModel(
  types: SchemaTypes,
  key: string,
  file: string,
  position: Position = 'output',
): TypeModel {
  const schema = types.schemaOf(key)!;
  const output = types.names.get(key)!;
  const name = position === 'input' ? types.inputNames.get(key)! : output;
  const description =
    typeof schema.description === 'string' ? schema.description : undefined;
  const references = [...types.namedReferences(schema)]
    .map(ref =>
      position === 'input'
        ? (types.inputNames.get(ref) ?? types.names.get(ref))
        : types.names.get(ref),
    )
    .filter((ref): ref is string => ref !== undefined && ref !== name);
  const base = {
    key,
    name,
    file,
    ...(description === undefined ? {} : { description }),
    ...(position === 'input' ? { inputOf: output } : {}),
    references: [...new Set(references)].sort(compareNames),
  };
  if (types.isInterface(schema)) {
    // A typed index signature would have to admit every property's type.
    const extra = types.additional(schema, key, position);
    return {
      ...base,
      properties: types.properties(schema, key, position),
      ...(extra === undefined ? {} : { type: 'unknown' }),
    };
  }
  return { ...base, type: types.type(schema, key, position) };
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
      ...new Set(
        (context.subscriptions ?? []).filter(isWaitHop).map(s => s.name),
      ),
    ].sort(compareNames),
    aggregates: (context.aggregates ?? []).map(aggregate =>
      aggregateModel(types, numbers, context, aggregate),
    ),
    commands: routingCommands(context.commands),
    routes: (context.routes ?? []).map(route =>
      serviceRouteModel(types, numbers, context, route),
    ),
  };
}

/**
 * Whether a wait chain can follow [subscription], so a `handled` hop may name
 * it: the Kotlin `SubscriptionManifest.isWaitHop`. A subscription is a hop when
 * it handles a commit: an event handler, and a saga's `@OnCommitted` handler; a
 * saga's `@OnRejected` handler alone is not, since the rejection it handles
 * ended the wait, and a handler of both receipts is.
 */
function isWaitHop(subscription: {
  readonly rejected?: readonly string[];
  readonly committed?: readonly string[];
}): boolean {
  return (
    subscription.rejected === undefined || subscription.committed !== undefined
  );
}
