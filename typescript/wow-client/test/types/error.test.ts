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
import { ErrorCodes, RecoverableType } from '../../src';

describe('ErrorCodes', () => {
  it('should have correct SUCCEEDED code and message', () => {
    expect(ErrorCodes.SUCCEEDED).toBe('Ok');
    expect(ErrorCodes.SUCCEEDED_MESSAGE).toBe('');
  });

  it('should have correct NOT_FOUND code and message', () => {
    expect(ErrorCodes.NOT_FOUND).toBe('NotFound');
    expect(ErrorCodes.NOT_FOUND_MESSAGE).toBe('Not found resource!');
  });

  it('should have correct BAD_REQUEST code', () => {
    expect(ErrorCodes.BAD_REQUEST).toBe('BadRequest');
  });

  it('should have correct ILLEGAL_ARGUMENT code', () => {
    expect(ErrorCodes.ILLEGAL_ARGUMENT).toBe('IllegalArgument');
  });

  it('should have correct ILLEGAL_STATE code', () => {
    expect(ErrorCodes.ILLEGAL_STATE).toBe('IllegalState');
  });

  it('should have correct REQUEST_TIMEOUT code', () => {
    expect(ErrorCodes.REQUEST_TIMEOUT).toBe('RequestTimeout');
  });

  it('should have correct TOO_MANY_REQUESTS code', () => {
    expect(ErrorCodes.TOO_MANY_REQUESTS).toBe('TooManyRequests');
  });

  it('should have correct DUPLICATE_REQUEST_ID code', () => {
    expect(ErrorCodes.DUPLICATE_REQUEST_ID).toBe('DuplicateRequestId');
  });

  it('should have correct COMMAND_VALIDATION code', () => {
    expect(ErrorCodes.COMMAND_VALIDATION).toBe('CommandValidation');
  });

  it('should have correct REWRITE_NO_COMMAND code', () => {
    expect(ErrorCodes.REWRITE_NO_COMMAND).toBe('RewriteNoCommand');
  });

  it('should have correct EVENT_VERSION_CONFLICT code', () => {
    expect(ErrorCodes.EVENT_VERSION_CONFLICT).toBe('EventVersionConflict');
  });

  it('should have correct DUPLICATE_AGGREGATE_ID code', () => {
    expect(ErrorCodes.DUPLICATE_AGGREGATE_ID).toBe('DuplicateAggregateId');
  });

  it('should have correct COMMAND_EXPECT_VERSION_CONFLICT code', () => {
    expect(ErrorCodes.COMMAND_EXPECT_VERSION_CONFLICT).toBe(
      'CommandExpectVersionConflict',
    );
  });

  it('should have correct SOURCING_VERSION_CONFLICT code', () => {
    expect(ErrorCodes.SOURCING_VERSION_CONFLICT).toBe(
      'SourcingVersionConflict',
    );
  });

  it('should have correct ILLEGAL_ACCESS_DELETED_AGGREGATE code', () => {
    expect(ErrorCodes.ILLEGAL_ACCESS_DELETED_AGGREGATE).toBe(
      'IllegalAccessDeletedAggregate',
    );
  });

  it('should have correct ILLEGAL_ACCESS_OWNER_AGGREGATE code', () => {
    expect(ErrorCodes.ILLEGAL_ACCESS_OWNER_AGGREGATE).toBe(
      'IllegalAccessOwnerAggregate',
    );
  });

  it('should have correct INTERNAL_SERVER_ERROR code', () => {
    expect(ErrorCodes.INTERNAL_SERVER_ERROR).toBe('InternalServerError');
  });

  it('should return true for isSucceeded when errorCode is SUCCEEDED', () => {
    expect(ErrorCodes.isSucceeded(ErrorCodes.SUCCEEDED)).toBe(true);
  });

  it('should return false for isSucceeded when errorCode is not SUCCEEDED', () => {
    expect(ErrorCodes.isSucceeded(ErrorCodes.NOT_FOUND)).toBe(false);
  });

  it('should return false for isError when errorCode is SUCCEEDED', () => {
    expect(ErrorCodes.isError(ErrorCodes.SUCCEEDED)).toBe(false);
  });

  it('should return true for isError when errorCode is not SUCCEEDED', () => {
    expect(ErrorCodes.isError(ErrorCodes.NOT_FOUND)).toBe(true);
  });
});

describe('RecoverableType', () => {
  it('should have correct RECOVERABLE value', () => {
    expect(RecoverableType.RECOVERABLE).toBe('RECOVERABLE');
  });

  it('should have correct UNKNOWN value', () => {
    expect(RecoverableType.UNKNOWN).toBe('UNKNOWN');
  });

  it('should have correct UNRECOVERABLE value', () => {
    expect(RecoverableType.UNRECOVERABLE).toBe('UNRECOVERABLE');
  });
});
