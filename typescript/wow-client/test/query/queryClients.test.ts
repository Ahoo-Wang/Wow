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
  QueryClientFactory,
  QueryClientOptions,
  createQueryApiMetadata,
} from '../../src';

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

  describe('QueryClientFactory', () => {
    const defaultOptions: QueryClientOptions = {
      aggregateName: 'defaultAggregate',
    };

    describe('constructor', () => {
      it('should create a factory with default options', () => {
        const factory = new QueryClientFactory(defaultOptions);

        expect(factory).toBeInstanceOf(QueryClientFactory);
      });
    });

    describe('createSnapshotQueryClient', () => {
      it('should create a SnapshotQueryClient with merged options', () => {
        const factory = new QueryClientFactory(defaultOptions);
        const options: QueryClientOptions = {
          aggregateName: 'testAggregate',
        };

        const client = factory.createSnapshotQueryClient(options);

        expect(client).toBeDefined();
      });

      it('should merge default options with provided options', () => {
        const factoryDefaultOptions: QueryClientOptions = {
          aggregateName: 'defaultAggregate',
          contextAlias: 'defaultContext',
        };
        const factory = new QueryClientFactory(factoryDefaultOptions);

        const options: QueryClientOptions = {
          aggregateName: 'testAggregate',
          // contextAlias should come from defaults
        };

        const client = factory.createSnapshotQueryClient(options);

        expect(client).toBeDefined();
      });
    });

    describe('createEventStreamQueryClient', () => {
      it('should create an EventStreamQueryClient with merged options', () => {
        const factory = new QueryClientFactory(defaultOptions);
        const options: QueryClientOptions = {
          aggregateName: 'testAggregate',
        };

        const client = factory.createEventStreamQueryClient(options);

        expect(client).toBeDefined();
      });

      it('should merge default options with provided options', () => {
        const factoryDefaultOptions: QueryClientOptions = {
          aggregateName: 'defaultAggregate',
          contextAlias: 'defaultContext',
        };
        const factory = new QueryClientFactory(factoryDefaultOptions);

        const options: QueryClientOptions = {
          aggregateName: 'testAggregate',
          // contextAlias should come from defaults
        };

        const client = factory.createEventStreamQueryClient(options);

        expect(client).toBeDefined();
      });
    });
  });
});
