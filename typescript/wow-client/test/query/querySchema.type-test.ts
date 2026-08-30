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

import { expectTypeOf } from 'vitest';
import {
  QueryCapability,
  QueryCardinality,
  QueryModel,
  QuerySemanticTypeValue,
  QueryValueType,
  TimeUnit,
  type QueryModelSchemaMetadata,
} from '../../src';

const schema: QueryModelSchemaMetadata = {
  model: QueryModel.SNAPSHOT,
  capabilities: [QueryCapability.SORT],
  fields: [
    {
      field: 'state.createdAt',
      title: 'Created At',
      description: null,
      enumValues: null,
      valueTypes: [QueryValueType.INTEGER],
      nullable: false,
      required: true,
      cardinality: QueryCardinality.SINGLE,
      semanticType: {
        type: QuerySemanticTypeValue.TEMPORAL_EPOCH,
        timeUnit: TimeUnit.SECONDS,
      },
      dynamicChildren: false,
      capabilities: [QueryCapability.RANGE],
      masked: false,
    },
  ],
};

const customModel: QueryModelSchemaMetadata['model'] = 'CUSTOM_MODEL';
const customCapability: QueryModelSchemaMetadata['capabilities'][number] =
  'CUSTOM_CAPABILITY';
const customValueType: QueryModelSchemaMetadata['fields'][number]['valueTypes'][number] =
  'CUSTOM_VALUE';

expectTypeOf(schema).toEqualTypeOf<QueryModelSchemaMetadata>();
expectTypeOf(customModel).toEqualTypeOf<string>();
expectTypeOf(customCapability).toEqualTypeOf<string>();
expectTypeOf(customValueType).toEqualTypeOf<string>();
