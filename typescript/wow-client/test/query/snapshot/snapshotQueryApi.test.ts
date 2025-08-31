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

import { describe, expect, it } from 'vitest';
import { SnapshotQueryEndpointPaths } from '../../../src';

describe('SnapshotQueryEndpointPaths', () => {
  it('should have correct endpoint path values', () => {
    expect(SnapshotQueryEndpointPaths.SNAPSHOT_RESOURCE_NAME).toBe('snapshot');
    expect(SnapshotQueryEndpointPaths.COUNT).toBe('snapshot/count');
    expect(SnapshotQueryEndpointPaths.LIST).toBe('snapshot/list');
    expect(SnapshotQueryEndpointPaths.LIST_STATE).toBe('snapshot/list/state');
    expect(SnapshotQueryEndpointPaths.PAGED).toBe('snapshot/paged');
    expect(SnapshotQueryEndpointPaths.PAGED_STATE).toBe('snapshot/paged/state');
    expect(SnapshotQueryEndpointPaths.SINGLE).toBe('snapshot/single');
    expect(SnapshotQueryEndpointPaths.SINGLE_STATE).toBe(
      'snapshot/single/state',
    );
  });
});
