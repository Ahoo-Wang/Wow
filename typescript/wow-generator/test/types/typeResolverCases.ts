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

import type { Components, Reference, Schema } from '@ahoo-wang/fetcher-openapi';

/**
 * The inputs of the table-driven `TypeResolver` cases: a schema, where its
 * type is written, and what the module imports already.
 */

const ref = (key: string): Reference => ({
  $ref: `#/components/schemas/${key}`,
});

/** The components every case resolves its references against. */
export const COMPONENTS: Components = {
  schemas: {
    Item: {
      type: 'object',
      properties: { id: { type: 'string' } },
      required: ['id'],
    },
    Status: { type: 'string', enum: ['ON', 'OFF'] },
    Response: { type: 'object', properties: { ok: { type: 'boolean' } } },
    Cat: { type: 'object', properties: { meow: { type: 'boolean' } } },
    Dog: { type: 'object', properties: { bark: { type: 'boolean' } } },
    Name: { type: 'string' },
    Tags: { type: 'array', items: { type: 'string' } },
    'shop.Order': {
      type: 'object',
      properties: { item: ref('Item') },
    },
    'shop.Item': { type: 'object', properties: { sku: { type: 'string' } } },
    'shop.Status': { type: 'string', enum: ['NEW'] },
    'wow.api.query.ListQuery': {
      type: 'object',
      properties: { filter: { type: 'object' } },
    },
    'wow.api.command.CommandResult': { type: 'object' },
  },
};

/**
 * Where a type is written. A model owns the declaration at `path`, so the
 * models of that path are declared beside it; an API client has no path, and
 * imports every model.
 */
export interface CaseScope {
  readonly owner: { readonly name: string; readonly path?: string };
  /** The directory of the module, under the output directory `/out`. */
  readonly directory: string;
  /** Imports the module holds before the type is resolved. */
  readonly existing?: readonly {
    moduleSpecifier: string;
    name: string;
    alias?: string;
  }[];
}

export const ROOT_MODEL: CaseScope = {
  owner: { name: 'Model', path: '/' },
  directory: '/out',
};
export const SHOP_MODEL: CaseScope = {
  owner: { name: 'Model', path: '/shop' },
  directory: '/out/shop',
};
export const API_CLIENT: CaseScope = {
  owner: { name: 'ItemsApiClient' },
  directory: '/out',
};

/** Which entry point of the resolver a case exercises. */
export type Entry =
  | 'type'
  | 'additionalProperties'
  | 'additionalPropertyType'
  | 'requiredAdditionalPropertyType'
  | 'mapValueType';

export interface ResolverCase {
  readonly name: string;
  readonly schema: Schema | Reference;
  readonly scope?: CaseScope;
  readonly entry?: Entry;
  /** Resolve the schema this many times into the same module. */
  readonly times?: number;
}

const object = (
  properties: Record<string, Schema | Reference>,
  extra: Schema = {},
): Schema => ({ type: 'object', properties, ...extra });

export const CASES: readonly ResolverCase[] = [
  // Primitives and arrays
  { name: 'string', schema: { type: 'string' } },
  { name: 'integer', schema: { type: 'integer' } },
  { name: 'number', schema: { type: 'number' } },
  { name: 'boolean', schema: { type: 'boolean' } },
  { name: 'null', schema: { type: 'null' } as Schema },
  { name: 'binary string', schema: { type: 'string', format: 'binary' } },
  { name: 'no type', schema: {} },
  {
    name: 'array of strings',
    schema: { type: 'array', items: { type: 'string' } },
  },
  { name: 'array without items', schema: { type: 'array' } as Schema },
  {
    name: 'array of a union',
    schema: { type: 'array', items: { type: ['string', 'integer'] } } as Schema,
  },
  {
    name: 'array of arrays',
    schema: {
      type: 'array',
      items: { type: 'array', items: { type: 'number' } },
    },
  },
  // Type arrays and nullability
  { name: 'type array', schema: { type: ['string', 'null'] } as Schema },
  {
    name: 'type array with an object',
    schema: {
      type: ['object', 'null'],
      properties: { a: { type: 'string' } },
    } as Schema,
  },
  { name: 'nullable string', schema: { type: 'string', nullable: true } },
  {
    name: 'nullable reference',
    schema: { nullable: true, allOf: [ref('Item')] },
  },
  {
    name: 'nullable reference in a oneOf',
    schema: { nullable: true, oneOf: [ref('Cat'), ref('Dog')] },
  },
  // References
  { name: 'reference beside the owner', schema: ref('Item') },
  { name: 'reference into another path', schema: ref('shop.Order') },
  {
    name: 'reference from another path to a name a model there takes',
    schema: ref('Item'),
    scope: SHOP_MODEL,
  },
  {
    name: 'reference to a sibling enum text name',
    schema: ref('Status'),
    scope: SHOP_MODEL,
  },
  {
    name: 'reference to a global name',
    schema: ref('Response'),
    scope: SHOP_MODEL,
  },
  {
    name: 'reference from an API client',
    schema: ref('Item'),
    scope: API_CLIENT,
  },
  {
    name: 'reference to a global name from an API client',
    schema: ref('Response'),
    scope: API_CLIENT,
  },
  {
    name: 'reference to a Wow type',
    schema: ref('wow.api.query.ListQuery'),
    scope: API_CLIENT,
  },
  {
    name: 'reference to a Wow command type',
    schema: ref('wow.api.command.CommandResult'),
  },
  {
    name: 'reference whose name another import took',
    schema: ref('Item'),
    scope: {
      ...API_CLIENT,
      existing: [{ moduleSpecifier: '@acme/items', name: 'Item' }],
    },
  },
  {
    name: 'reference whose alias another import took',
    schema: ref('Response'),
    scope: {
      ...API_CLIENT,
      existing: [{ moduleSpecifier: '@acme/x', name: 'Y', alias: '_Response' }],
    },
  },
  {
    name: 'reference already imported under an alias',
    schema: ref('Item'),
    scope: {
      ...API_CLIENT,
      existing: [{ moduleSpecifier: './types.js', name: 'Item', alias: 'I' }],
    },
  },
  {
    name: 'reference named like the owner',
    schema: ref('Item'),
    scope: { owner: { name: 'Item' }, directory: '/out' },
  },
  {
    name: 'reference resolved twice',
    schema: object({ a: ref('Response'), b: ref('Response') }),
    scope: SHOP_MODEL,
    times: 2,
  },
  {
    name: 'array of references',
    schema: { type: 'array', items: ref('shop.Order') },
  },
  // Compositions
  { name: 'allOf of one reference', schema: { allOf: [ref('Item')] } },
  {
    name: 'allOf of references with a description',
    schema: { allOf: [ref('Item'), ref('Cat')], description: 'both' },
  },
  {
    name: 'anyOf of a reference and null',
    schema: { anyOf: [ref('Item'), { type: 'null' } as Schema] },
  },
  {
    name: 'oneOf with a discriminator',
    schema: {
      oneOf: [ref('Cat'), ref('Dog')],
      discriminator: { propertyName: 'petType' },
    },
  },
  {
    name: 'oneOf with a discriminator mapping',
    schema: {
      oneOf: [ref('Cat'), ref('Dog')],
      discriminator: {
        propertyName: 'pet-type',
        mapping: {
          cat: '#/components/schemas/Cat',
          kitty: 'Cat',
          dog: '#/components/schemas/Dog',
        },
      },
    },
  },
  {
    name: 'discriminator on an inline member',
    schema: {
      oneOf: [ref('Cat'), object({ fish: { type: 'boolean' } })],
      discriminator: { propertyName: 'petType' },
    },
  },
  {
    name: 'allOf with inline members',
    schema: {
      allOf: [ref('Item'), object({ extra: { type: 'string' } })],
    },
  },
  {
    name: 'oneOf of primitives',
    schema: { oneOf: [{ type: 'string' }, { type: 'integer' }] },
  },
  {
    name: 'anyOf with an untyped member',
    schema: { anyOf: [{ type: 'string' }, {}] },
  },
  {
    name: 'composition beside a type',
    schema: {
      type: 'object',
      properties: { a: { type: 'string' } },
      allOf: [ref('Item')],
    },
  },
  {
    name: 'composition of several keywords',
    schema: { allOf: [ref('Item')], oneOf: [ref('Cat'), ref('Dog')] },
  },
  {
    name: 'object-constrained composition',
    schema: {
      oneOf: [
        object({ a: { type: 'string' } }),
        object({ b: { type: 'string' } }),
      ],
      required: ['a'],
    },
  },
  {
    name: 'composition constrained through references',
    schema: { anyOf: [ref('Cat'), ref('Dog')], minProperties: 1 },
  },
  {
    name: 'composition of an object and a string',
    schema: {
      anyOf: [object({ a: { type: 'string' } }), { type: 'string' }],
      maxLength: 3,
    },
  },
  // const
  { name: 'string const', schema: { const: 'fixed' } as Schema },
  {
    name: 'string const with a type',
    schema: { type: 'string', const: "it's" } as Schema,
  },
  {
    name: 'const that does not match its type',
    schema: { type: 'integer', const: 'x' } as Schema,
  },
  {
    name: 'object const',
    schema: { const: { a: 1, 'b-c': [true, null] } } as Schema,
  },
  { name: 'empty object const', schema: { const: {} } as Schema },
  { name: 'null const', schema: { const: null } as Schema },
  // Enums
  { name: 'string enum', schema: { type: 'string', enum: ['A', 'b-c'] } },
  { name: 'integer enum', schema: { type: 'integer', enum: [1, 2, 2.5] } },
  { name: 'untyped enum', schema: { enum: ['a', 1, null, true] } },
  {
    name: 'nullable enum',
    schema: { type: 'string', nullable: true, enum: ['a', null] },
  },
  {
    name: 'enum with no matching value',
    schema: { type: 'boolean', enum: ['x'] },
  },
  {
    name: 'object enum',
    schema: { type: 'object', enum: [{ a: 1 }] } as Schema,
  },
  // Maps
  {
    name: 'map of strings',
    schema: { type: 'object', additionalProperties: { type: 'string' } },
  },
  {
    name: 'map of anything',
    schema: { type: 'object', additionalProperties: true },
  },
  {
    name: 'map with a key schema',
    schema: {
      type: 'object',
      additionalProperties: ref('Item'),
      'x-map-key-schema': ref('Status'),
    } as Schema,
  },
  {
    name: 'map with a key schema and properties',
    schema: {
      type: 'object',
      properties: { a: { type: 'string' } },
      additionalProperties: { type: 'integer' },
      'x-map-key-schema': { type: 'string', enum: ['a', 'b'] },
    } as Schema,
  },
  {
    name: 'map with required keys',
    schema: {
      type: 'object',
      additionalProperties: { type: 'number' },
      required: ['total'],
    },
  },
  // Objects
  { name: 'empty object', schema: { type: 'object' } },
  {
    name: 'object with properties',
    schema: object({
      id: { type: 'string', readOnly: true },
      'display-name': { type: 'string', description: 'Shown to people' },
      item: ref('Item'),
    }),
  },
  {
    name: 'object without additional properties',
    schema: object({ a: { type: 'string' } }, { additionalProperties: false }),
  },
  {
    name: 'object with open additional properties',
    schema: object({ a: { type: 'string' } }, { additionalProperties: true }),
  },
  {
    name: 'object with typed additional properties',
    schema: object(
      { a: { type: 'string' } },
      { additionalProperties: { type: 'string' } },
    ),
  },
  {
    name: 'object whose property clashes with its index',
    schema: object(
      { count: { type: 'integer' } },
      { additionalProperties: { type: 'string' } },
    ),
  },
  {
    name: 'object whose referenced property clashes with its index',
    schema: object(
      { name: ref('Name'), tags: ref('Tags') },
      { additionalProperties: { type: 'string' } },
    ),
  },
  {
    name: 'object with an allOf property beside an index',
    schema: object(
      { name: { allOf: [ref('Name')] } },
      { additionalProperties: { type: 'string' } },
    ),
  },
  {
    name: 'object with a nullable property beside an index',
    schema: object(
      { name: { type: 'string', nullable: true } },
      { additionalProperties: { type: 'string' } },
    ),
  },
  {
    name: 'object with required names it does not declare',
    schema: object({ a: { type: 'string' } }, { required: ['a', 'b'] }),
  },
  {
    name: 'object requiring names with no additional properties',
    schema: object({}, { required: ['b'], additionalProperties: false }),
  },
  {
    name: 'object requiring names with typed additional properties',
    schema: object(
      {},
      { required: ['b'], additionalProperties: { type: 'boolean' } },
    ),
  },
  {
    name: 'untyped schema with properties',
    schema: { properties: { a: { type: 'string' } }, required: ['a'] },
  },
  { name: 'untyped schema with required names', schema: { required: ['a'] } },
  // The other entry points
  {
    name: 'additional properties, closed',
    schema: object({ a: { type: 'string' } }, { additionalProperties: false }),
    entry: 'additionalProperties',
  },
  {
    name: 'additional properties, not declared',
    schema: object({ a: { type: 'string' } }),
    entry: 'additionalProperties',
  },
  {
    name: 'additional properties, open',
    schema: object({}, { additionalProperties: true }),
    entry: 'additionalProperties',
  },
  {
    name: 'additional properties, required but undeclared',
    schema: object({}, { required: ['x'] }),
    entry: 'additionalProperties',
  },
  {
    name: 'additional properties, a reference',
    schema: object({}, { additionalProperties: ref('Response') }),
    scope: SHOP_MODEL,
    entry: 'additionalProperties',
  },
  {
    name: 'additional property type, a schema',
    schema: object({}, { additionalProperties: { type: 'integer' } }),
    entry: 'additionalPropertyType',
  },
  {
    name: 'additional property type, open',
    schema: object({}, { additionalProperties: true }),
    entry: 'additionalPropertyType',
  },
  {
    name: 'required additional property type, closed',
    schema: object({}, { additionalProperties: false }),
    entry: 'requiredAdditionalPropertyType',
  },
  {
    name: 'required additional property type, a schema',
    schema: object({}, { additionalProperties: ref('shop.Order') }),
    entry: 'requiredAdditionalPropertyType',
  },
  {
    name: 'required additional property type, open',
    schema: object({}),
    entry: 'requiredAdditionalPropertyType',
  },
  {
    name: 'map value type, a schema',
    schema: { type: 'object', additionalProperties: ref('Item') },
    scope: API_CLIENT,
    entry: 'mapValueType',
  },
  {
    name: 'map value type, open',
    schema: { type: 'object', additionalProperties: true },
    entry: 'mapValueType',
  },
];
