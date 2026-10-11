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

import { analyticsBindings, datasetPath, withView } from './routes.ts';

describe('routes', () => {
  it('puts the open view in the address', () => {
    expect(withView('/boards', null)).toBe('/boards');
    expect(withView('/boards', 'b 1')).toBe('/boards?view=b+1');
    expect(datasetPath('analytics.orders')).toBe('/datasets/analytics.orders');
    expect(datasetPath('analytics.orders', 'v1')).toBe(
      '/datasets/analytics.orders?view=v1',
    );
  });

  it('binds every served dataset and the boards', () => {
    expect(
      analyticsBindings(['analytics.orders']).map(
        binding => binding.definitionId,
      ),
    ).toEqual(['analytics.orders', 'boards']);
  });
});
