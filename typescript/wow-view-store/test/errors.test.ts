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

/**
 * The server's answers onto the port's five codes (view-store-backend.md
 * 6.2): by Wow's error code first, by the HTTP status only for an answer
 * without a code the store knows.
 */

import { describe, expect, it } from 'vitest';
import { ErrorCodes } from '@ahoo-wang/wow-client';
import { portCodeOf } from '../src/errors.js';
import { WowViewStoreErrorCodes } from '../src/index.js';

describe('portCodeOf', () => {
  it.each([
    [ErrorCodes.COMMAND_EXPECT_VERSION_CONFLICT, 'CONFLICT'],
    [ErrorCodes.EVENT_VERSION_CONFLICT, 'CONFLICT'],
    [ErrorCodes.SOURCING_VERSION_CONFLICT, 'CONFLICT'],
    [ErrorCodes.NOT_FOUND, 'NOT_FOUND'],
    [ErrorCodes.ILLEGAL_ACCESS_DELETED_AGGREGATE, 'NOT_FOUND'],
    [ErrorCodes.ILLEGAL_ACCESS_OWNER_AGGREGATE, 'FORBIDDEN'],
    [ErrorCodes.ILLEGAL_ACCESS_SPACE_AGGREGATE, 'FORBIDDEN'],
    [ErrorCodes.ILLEGAL_ACCESS_QUERY_SCOPE, 'FORBIDDEN'],
    [WowViewStoreErrorCodes.SYSTEM_VIEW_READ_ONLY, 'FORBIDDEN'],
    [WowViewStoreErrorCodes.VIEW_EVENT_STREAM_CLOSED, 'FORBIDDEN'],
    [WowViewStoreErrorCodes.VIEW_INVALID, 'INVALID'],
    [WowViewStoreErrorCodes.VIEW_APP_REQUIRED, 'INVALID'],
    [WowViewStoreErrorCodes.VIEW_SCOPE_REQUIRED, 'INVALID'],
    [ErrorCodes.COMMAND_VALIDATION, 'INVALID'],
    [ErrorCodes.ILLEGAL_ARGUMENT, 'INVALID'],
    [ErrorCodes.BAD_REQUEST, 'INVALID'],
    [ErrorCodes.DUPLICATE_AGGREGATE_ID, 'INVALID'],
    [ErrorCodes.QUERY_SCHEMA_VALIDATION, 'INVALID'],
    [ErrorCodes.ILLEGAL_STATE, 'UNAVAILABLE'],
    [ErrorCodes.QUERY_SCHEMA_UNAVAILABLE, 'UNAVAILABLE'],
    [ErrorCodes.QUERY_SCHEMA_CONFLICT, 'UNAVAILABLE'],
    [ErrorCodes.REQUEST_TIMEOUT, 'UNAVAILABLE'],
    [ErrorCodes.TOO_MANY_REQUESTS, 'UNAVAILABLE'],
    [ErrorCodes.INTERNAL_SERVER_ERROR, 'UNAVAILABLE'],
  ])('reads %s as %s, whatever the status', (errorCode, code) => {
    for (const status of [400, 403, 404, 409, 500, undefined])
      expect(portCodeOf(errorCode, status)).toBe(code);
  });

  it.each([
    [400, 'INVALID'],
    [422, 'INVALID'],
    [401, 'FORBIDDEN'],
    [403, 'FORBIDDEN'],
    [404, 'NOT_FOUND'],
    [410, 'NOT_FOUND'],
    [409, 'CONFLICT'],
    [412, 'CONFLICT'],
    [502, 'UNAVAILABLE'],
    [undefined, 'UNAVAILABLE'],
  ])('falls back to the status %s without a known code', (status, code) => {
    expect(portCodeOf(undefined, status)).toBe(code);
    expect(portCodeOf('SomeLaterCode', status)).toBe(code);
  });
});
