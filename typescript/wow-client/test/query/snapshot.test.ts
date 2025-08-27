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
import { MaterializedSnapshot, SmallMaterializedSnapshot } from '../../src';

interface TestState {
  name: string;
  value: number;
}

describe('MaterializedSnapshot', () => {
  it('should create a materialized snapshot with all required properties', () => {
    const snapshot: MaterializedSnapshot<TestState> = {
      contextName: 'test-context',
      aggregateName: 'test-aggregate',
      tenantId: 'tenant-1',
      ownerId: 'owner-1',
      version: 1,
      eventId: 'event-1',
      firstOperator: 'operator-1',
      operator: 'operator-2',
      firstEventTime: Date.now(),
      eventTime: Date.now(),
      snapshotTime: Date.now(),
      deleted: false,
      state: {
        name: 'test',
        value: 42,
      },
    };

    expect(snapshot).toBeDefined();
    expect(snapshot.contextName).toBe('test-context');
    expect(snapshot.aggregateName).toBe('test-aggregate');
    expect(snapshot.tenantId).toBe('tenant-1');
    expect(snapshot.ownerId).toBe('owner-1');
    expect(snapshot.version).toBe(1);
    expect(snapshot.eventId).toBe('event-1');
    expect(snapshot.firstOperator).toBe('operator-1');
    expect(snapshot.operator).toBe('operator-2');
    expect(snapshot.state.name).toBe('test');
    expect(snapshot.state.value).toBe(42);
  });
});

describe('SmallMaterializedSnapshot', () => {
  it('should create a small materialized snapshot with required properties', () => {
    const smallSnapshot: SmallMaterializedSnapshot<TestState> = {
      contextName: 'test-context',
      aggregateName: 'test-aggregate',
      version: 1,
      firstEventTime: Date.now(),
      state: {
        name: 'test',
        value: 42,
      },
    };

    expect(smallSnapshot).toBeDefined();
    expect(smallSnapshot.contextName).toBe('test-context');
    expect(smallSnapshot.aggregateName).toBe('test-aggregate');
    expect(smallSnapshot.version).toBe(1);
    expect(smallSnapshot.state.name).toBe('test');
    expect(smallSnapshot.state.value).toBe(42);
  });
});
