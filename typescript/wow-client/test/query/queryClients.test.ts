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

import { describe, expect, it, vi } from 'vitest';
import {
  createQueryApiMetadata,
  createSnapshotQueryClient,
  createEventStreamQueryClient,
  QueryClientOptions,
} from '../../src';

// Mock the SnapshotQueryClient and EventStreamQueryClient classes
vi.mock('../../src/query/snapshot', () => {
  return {
    SnapshotQueryClient: vi.fn().mockImplementation(() => {
      return {};
    }),
  };
});

vi.mock('../../src/query/event', () => {
  return {
    EventStreamQueryClient: vi.fn().mockImplementation(() => {
      return {};
    }),
  };
});

describe('queryClients', () => {
  describe('createQueryApiMetadata', () => {
    it('should create API metadata with basic path', () => {
      const options: QueryClientOptions = {
        aggregateName: 'testAggregate',
      };

      const result = createQueryApiMetadata(options);

      expect(result.aggregateName).toBe('testAggregate');
      expect(result.basePath).toBe('/testAggregate');
    });

    it('should create API metadata with resource attribution path', () => {
      const options: QueryClientOptions = {
        aggregateName: 'testAggregate',
        resourceAttribution: 'resources',
      };

      const result = createQueryApiMetadata(options);

      expect(result.aggregateName).toBe('testAggregate');
      expect(result.basePath).toBe('resources/testAggregate');
    });

    it('should create API metadata with context alias', () => {
      const options: QueryClientOptions = {
        aggregateName: 'testAggregate',
        contextAlias: 'testContext',
      };

      const result = createQueryApiMetadata(options);

      expect(result.aggregateName).toBe('testAggregate');
      expect(result.basePath).toBe('testContext/testAggregate');
    });

    it('should create API metadata with both resource attribution and context alias', () => {
      const options: QueryClientOptions = {
        aggregateName: 'testAggregate',
        resourceAttribution: 'resources',
        contextAlias: 'testContext',
      };

      const result = createQueryApiMetadata(options);

      expect(result.aggregateName).toBe('testAggregate');
      expect(result.basePath).toBe('testContext/resources/testAggregate');
    });
  });

  describe('createSnapshotQueryClient', () => {
    it('should create a SnapshotQueryClient instance', () => {
      const options: QueryClientOptions = {
        aggregateName: 'testAggregate',
      };

      const client = createSnapshotQueryClient(options);

      expect(client).toBeDefined();
    });
  });

  describe('createEventStreamQueryClient', () => {
    it('should create an EventStreamQueryClient instance', () => {
      const options: QueryClientOptions = {
        aggregateName: 'testAggregate',
      };

      const client = createEventStreamQueryClient(options);

      expect(client).toBeDefined();
    });
  });
});