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
import { CommandRequest } from '../../src';
import { HttpMethod } from '@ahoo-wang/fetcher';

describe('CommandRequest', () => {
  it('should create a command request with minimal properties', () => {
    const commandRequest: CommandRequest = {
      path: '/commands/CreateUser',
      method: HttpMethod.POST,
      headers: {},
      body: {
        name: 'John Doe',
        email: 'john@example.com',
      },
    };

    expect(commandRequest).toBeDefined();
    expect(commandRequest.path).toBe('/commands/CreateUser');
    expect(commandRequest.method).toBe(HttpMethod.POST);
    expect(commandRequest.headers).toEqual({});
    expect(commandRequest.body).toEqual({
      name: 'John Doe',
      email: 'john@example.com',
    });
  });

  it('should create a command request with all optional properties', () => {
    const commandRequest: CommandRequest = {
      path: '/commands/UpdateUser',
      method: HttpMethod.PUT,
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer token',
      },
      body: {
        id: 'user-123',
        name: 'Jane Doe',
        email: 'jane@example.com',
      },
      timeout: 5000,
      aggregateId: 'agg-456',
      aggregateVersion: 1,
      requestId: 'req-789',
      localFirst: true,
      stream: false,
    };

    expect(commandRequest).toBeDefined();
    expect(commandRequest.path).toBe('/commands/UpdateUser');
    expect(commandRequest.method).toBe(HttpMethod.PUT);
    expect(commandRequest.headers).toEqual({
      'Content-Type': 'application/json',
      Authorization: 'Bearer token',
    });
    expect(commandRequest.body).toEqual({
      id: 'user-123',
      name: 'Jane Doe',
      email: 'jane@example.com',
    });
    expect(commandRequest.timeout).toBe(5000);
    expect(commandRequest.aggregateId).toBe('agg-456');
    expect(commandRequest.aggregateVersion).toBe(1);
    expect(commandRequest.requestId).toBe('req-789');
    expect(commandRequest.localFirst).toBe(true);
    expect(commandRequest.stream).toBe(false);
  });

  it('should create a command request with path parameters', () => {
    const commandRequest: CommandRequest = {
      path: '/commands/users/{userId}',
      pathParams: {
        userId: 'user-123',
      },
      method: HttpMethod.DELETE,
      headers: {},
      body: {},
    };

    expect(commandRequest).toBeDefined();
    expect(commandRequest.path).toBe('/commands/users/{userId}');
    expect(commandRequest.pathParams).toEqual({
      userId: 'user-123',
    });
    expect(commandRequest.method).toBe(HttpMethod.DELETE);
  });
});
