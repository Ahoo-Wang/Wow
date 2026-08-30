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

import type { LogicalField } from './filter';
import type { TimeUnit } from './filter';

export const QueryModel = {
  SNAPSHOT: 'SNAPSHOT',
  EVENT_STREAM: 'EVENT_STREAM',
} as const;
export type QueryModel = string;

export const QueryCapability = {
  PRESENCE: 'PRESENCE',
  EXACT_MATCH: 'EXACT_MATCH',
  LITERAL_MATCH: 'LITERAL_MATCH',
  RANGE: 'RANGE',
  FULL_TEXT_TERMS: 'FULL_TEXT_TERMS',
  FULL_TEXT_PHRASE: 'FULL_TEXT_PHRASE',
  SORT: 'SORT',
  ELEMENT_SCOPE: 'ELEMENT_SCOPE',
  AGGREGATE_TERMS: 'AGGREGATE_TERMS',
  AGGREGATE_NUMERIC: 'AGGREGATE_NUMERIC',
  AGGREGATE_TEMPORAL: 'AGGREGATE_TEMPORAL',
} as const;
export type QueryCapability = string;

export const QueryValueType = {
  STRING: 'STRING',
  INTEGER: 'INTEGER',
  DECIMAL: 'DECIMAL',
  BOOLEAN: 'BOOLEAN',
  OBJECT: 'OBJECT',
} as const;
export type QueryValueType = string;

export enum QueryCardinality {
  SINGLE = 'SINGLE',
  MANY = 'MANY',
}

export enum QueryCompatibilityLevel {
  EXACT = 'EXACT',
  COMPATIBLE = 'COMPATIBLE',
  INCOMPATIBLE = 'INCOMPATIBLE',
}

export const QuerySemanticTypeValue = {
  TEMPORAL_DATE: 'TEMPORAL_DATE',
  TEMPORAL_EPOCH: 'TEMPORAL_EPOCH',
  TEMPORAL_FORMATTED: 'TEMPORAL_FORMATTED',
} as const;

export type QuerySemanticType =
  | { type: typeof QuerySemanticTypeValue.TEMPORAL_DATE }
  | {
      type: typeof QuerySemanticTypeValue.TEMPORAL_EPOCH;
      timeUnit?: TimeUnit;
    }
  | {
      type: typeof QuerySemanticTypeValue.TEMPORAL_FORMATTED;
      pattern: string;
    };

export interface QueryFieldSchemaMetadata<FIELDS extends string = string> {
  field: LogicalField<FIELDS>;
  title: string | null;
  description: string | null;
  enumValues: unknown[] | null;
  valueTypes: QueryValueType[];
  nullable: boolean;
  required: boolean;
  cardinality: QueryCardinality;
  semanticType: QuerySemanticType | null;
  dynamicChildren: boolean;
  capabilities: QueryCapability[];
  masked?: boolean;
}

export interface QueryModelSchemaMetadata<FIELDS extends string = string> {
  model: QueryModel;
  capabilities: QueryCapability[];
  fields: QueryFieldSchemaMetadata<FIELDS>[];
}
