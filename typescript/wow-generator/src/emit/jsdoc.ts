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
import type { JSDocableNodeStructure } from 'ts-morph';

/**
 * Generates a JSDoc comment string from a title and description.
 * @returns The formatted JSDoc string or undefined if both title and description are empty
 */
export function jsDoc(
  descriptions: (string | undefined)[],
  separator = '\n',
): string | undefined {
  if (!Array.isArray(descriptions)) {
    return undefined;
  }
  const filtered = descriptions
    .filter((v): v is string => typeof v === 'string' && v.length > 0)
    .map(escapeJsDoc);
  return filtered.length > 0 ? filtered.join(separator) : undefined;
}

/**
 * Keeps document text from ending the comment it is written into: a
 * description holding `*` followed by `/` - a cron expression such as
 * `*` `/5 * * * *`, a glob - would otherwise close the JSDoc early.
 *
 * @param text - Text taken from the document
 * @returns The text with every comment terminator broken up
 */
export function escapeJsDoc(text: string): string {
  return text.replace(/\*\//g, '*\\/');
}

/**
 * Adds a JSDoc comment to a declaration with the provided title and
 * description, after the ones it already has.
 */
export function addJSDoc(
  node: JSDocableNodeStructure,
  descriptions: (string | undefined)[],
) {
  const jsdoc = jsDoc(descriptions);
  if (!jsdoc) {
    return;
  }
  node.docs = [...(node.docs ?? []), jsdoc];
}

/**
 * The doc comment lines of a schema.
 *
 * A nullable value written as `anyOf: [{type: null}, X]` (or `oneOf`) is
 * documented from its non-null branch too, so its `format` and constraints
 * are not lost; what the schema itself says comes first.
 *
 * @param schema - The schema
 * @param key - The schema's component key, given for a model
 * @param main - Whether the comment is a model's, which also lists the
 * properties its schema requires
 */
export function schemaJSDoc(schema: Schema, key?: string, main = false) {
  schema = withNonNullBranch(schema);
  const descriptions: (string | undefined)[] = [
    schema.title,
    schema.description,
  ];
  if (key) {
    descriptions.push(`- key: ${key}`);
  }
  if (main) {
    addRequiredJsDoc(descriptions, schema);
  }
  if (schema.format) {
    descriptions.push(
      schema.format === 'int64'
        ? '- format: int64 (a value beyond Number.MAX_SAFE_INTEGER loses precision)'
        : `- format: ${schema.format}`,
    );
  }

  addJsonJsDoc(descriptions, schema, 'default');
  addJsonJsDoc(descriptions, schema, 'example');
  addNumericConstraintsJsDoc(descriptions, schema);
  addStringConstraintsJsDoc(descriptions, schema);
  addArrayConstraintsJsDoc(descriptions, schema);
  return descriptions;
}

/**
 * Adds a JSDoc comment to a declaration based on the schema's title and description.
 * @param node - The declaration to add the JSDoc comment to
 * @param schema - The schema containing title and description
 * @param key - The key associated with the schema
 */
export function addSchemaJSDoc(
  node: JSDocableNodeStructure,
  schema: Schema | Reference,
  key?: string,
) {
  const descriptions = schemaJSDoc(schema as Schema, key);
  addJSDoc(node, descriptions);
}

/**
 * Adds the doc comment of a model.
 *
 * @param node - The model declaration
 * @param schema - The schema it is generated from
 * @param key - The schema's component key
 * @param includeSchema - Also embed the complete JSON schema
 */
export function addMainSchemaJSDoc(
  node: JSDocableNodeStructure,
  schema: Schema | Reference,
  key?: string,
  includeSchema = false,
) {
  const descriptions = schemaJSDoc(schema as Schema, key, true);
  if (includeSchema) {
    jsonJsDoc(descriptions, 'schema', schema);
  }
  addJSDoc(node, descriptions);
}

/**
 * Merges the single non-null branch of a nullable composition under the
 * schema, so the schema's own keywords win. Any other schema is returned as
 * it is: a branch that is a reference documents itself.
 */
function withNonNullBranch(schema: Schema): Schema {
  for (const keyword of ['anyOf', 'oneOf'] as const) {
    const members = schema[keyword];
    if (!Array.isArray(members) || members.length < 2) continue;
    const nonNull = members.filter(
      member => '$ref' in member || member.type !== 'null',
    );
    const [branch] = nonNull;
    if (nonNull.length !== 1 || '$ref' in branch) continue;
    const merged: Schema = { ...branch };
    for (const [name, value] of Object.entries(schema)) {
      if (name !== keyword && value !== undefined) {
        (merged as Record<string, unknown>)[name] = value;
      }
    }
    return merged;
  }
  return schema;
}

/**
 * Lists the properties an object schema requires, which the generated type
 * does not tell: every generated property is declared without `?`.
 */
function addRequiredJsDoc(
  descriptions: (string | undefined)[],
  schema: Schema,
) {
  const required = schema.required ?? [];
  if (!required.length && !Object.keys(schema.properties ?? {}).length) {
    return;
  }
  descriptions.push(
    `- required: ${required.length ? required.join(', ') : '(none)'}`,
  );
}

function addJsonJsDoc(
  descriptions: (string | undefined)[],
  schema: any,
  propertyName: keyof Schema,
) {
  const json = schema[propertyName];
  if (!json) {
    return;
  }
  if (typeof json !== 'object') {
    descriptions.push(`- ${propertyName}: \`${json}\``);
    return;
  }
  jsonJsDoc(descriptions, propertyName, json);
}

function jsonJsDoc(
  descriptions: (string | undefined)[],
  name: string,
  json: any,
) {
  descriptions.push(`- ${name}: `);
  descriptions.push('```json');
  descriptions.push(JSON.stringify(json, null, 2));
  descriptions.push('```');
}

function addNumericConstraintsJsDoc(
  descriptions: (string | undefined)[],
  schema: Schema,
) {
  const constraintsDescriptions = ['- Numeric Constraints'];
  if (schema.minimum !== undefined) {
    constraintsDescriptions.push(`  - minimum: ${schema.minimum}`);
  }
  if (schema.maximum !== undefined) {
    constraintsDescriptions.push(`  - maximum: ${schema.maximum}`);
  }
  if (schema.exclusiveMinimum !== undefined) {
    constraintsDescriptions.push(
      `  - exclusiveMinimum: ${schema.exclusiveMinimum}`,
    );
  }
  if (schema.exclusiveMaximum !== undefined) {
    constraintsDescriptions.push(
      `  - exclusiveMaximum: ${schema.exclusiveMaximum}`,
    );
  }
  if (schema.multipleOf !== undefined) {
    constraintsDescriptions.push(`  - multipleOf: ${schema.multipleOf}`);
  }
  if (constraintsDescriptions.length === 1) {
    return;
  }
  descriptions.push(...constraintsDescriptions);
}

function addStringConstraintsJsDoc(
  descriptions: (string | undefined)[],
  schema: Schema,
) {
  const constraintsDescriptions = ['- String Constraints'];
  if (schema.minLength !== undefined) {
    constraintsDescriptions.push(`  - minLength: ${schema.minLength}`);
  }
  if (schema.maxLength !== undefined) {
    constraintsDescriptions.push(`  - maxLength: ${schema.maxLength}`);
  }
  if (schema.pattern !== undefined) {
    constraintsDescriptions.push(`  - pattern: ${schema.pattern}`);
  }
  if (constraintsDescriptions.length === 1) {
    return;
  }
  descriptions.push(...constraintsDescriptions);
}

function addArrayConstraintsJsDoc(
  descriptions: (string | undefined)[],
  schema: Schema,
) {
  const constraintsDescriptions = ['- Array Constraints'];
  if (schema.minItems !== undefined) {
    constraintsDescriptions.push(`  - minItems: ${schema.minItems}`);
  }
  if (schema.maxItems !== undefined) {
    constraintsDescriptions.push(`  - maxItems: ${schema.maxItems}`);
  }
  if (schema.uniqueItems !== undefined) {
    constraintsDescriptions.push(`  - uniqueItems: ${schema.uniqueItems}`);
  }
  if (constraintsDescriptions.length === 1) {
    return;
  }
  descriptions.push(...constraintsDescriptions);
}
