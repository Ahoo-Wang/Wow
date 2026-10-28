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

import { describe, expectTypeOf, it } from 'vitest';
import type { AnalysisMetric } from '../src/index.js';
import type {
  CARTESIAN_MISSING,
  CartesianMissing,
  DERIVED_KINDS,
  DerivedKind,
  REFERENCE_STATISTICS,
  ReferenceStatistic,
} from '../src/model/chart.js';
import type {
  FIELD_METRIC_TYPES,
  FieldMetricType,
} from '../src/model/analysis.js';
import type {
  ComparisonOperatorName,
  DURATION_COMPARISONS,
} from '../src/filter/values.js';

/**
 * A public union is written out as its literals, and the list the engine
 * offers them from stays internal. The list's `satisfies` refuses a value
 * the union lacks; these hold the other way, so a literal added to the
 * union without its place in the list fails to compile.
 */
describe('the choice lists and the unions they offer', () => {
  it('list every member of the union, and nothing else', () => {
    expectTypeOf<(typeof DERIVED_KINDS)[number]>().toEqualTypeOf<DerivedKind>();
    expectTypeOf<
      (typeof CARTESIAN_MISSING)[number]
    >().toEqualTypeOf<CartesianMissing>();
    expectTypeOf<
      (typeof REFERENCE_STATISTICS)[number]
    >().toEqualTypeOf<ReferenceStatistic>();
    expectTypeOf<
      (typeof FIELD_METRIC_TYPES)[number]
    >().toEqualTypeOf<FieldMetricType>();
    expectTypeOf<
      (typeof DURATION_COMPARISONS)[number]
    >().toEqualTypeOf<ComparisonOperatorName>();
  });

  it('keep a field metric type one a metric has', () => {
    expectTypeOf<FieldMetricType>().toExtend<AnalysisMetric['type']>();
  });
});
