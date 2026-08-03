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

import {
  and,
  type Condition,
  eq,
  gt,
  isIn,
  lte,
  or,
  RecoverableType,
} from "@ahoo-wang/fetcher-wow";
import {
  ExecutionFailedAggregatedFields,
  ExecutionFailedStatus,
} from "../../generated/compensation/execution_failed/types.ts";
import { FindCategory } from "./FindCategory.ts";

const RETRYABLE_RECOVERABILITY = [
  RecoverableType.RECOVERABLE,
  RecoverableType.UNKNOWN,
];

const ACTIVE_STATUSES = [
  ExecutionFailedStatus.FAILED,
  ExecutionFailedStatus.PREPARED,
];

export class RetryConditions {
  static toRetryCondition(now: number): Condition {
    return and(
      isIn(
        ExecutionFailedAggregatedFields.STATE_RECOVERABLE,
        ...RETRYABLE_RECOVERABILITY,
      ),
      eq(ExecutionFailedAggregatedFields.STATE_IS_RETRYABLE, true),
      or(
        eq(
          ExecutionFailedAggregatedFields.STATE_STATUS,
          ExecutionFailedStatus.FAILED,
        ),
        and(
          eq(
            ExecutionFailedAggregatedFields.STATE_STATUS,
            ExecutionFailedStatus.PREPARED,
          ),
          lte(
            ExecutionFailedAggregatedFields.STATE_RETRY_STATE_TIMEOUT_AT,
            now,
          ),
        ),
      ),
    );
  }

  static executingCondition(now: number): Condition {
    return and(
      eq(
        ExecutionFailedAggregatedFields.STATE_STATUS,
        ExecutionFailedStatus.PREPARED,
      ),
      gt(ExecutionFailedAggregatedFields.STATE_RETRY_STATE_TIMEOUT_AT, now),
    );
  }

  static nextRetryCondition(now: number): Condition {
    return and(
      isIn(
        ExecutionFailedAggregatedFields.STATE_RECOVERABLE,
        ...RETRYABLE_RECOVERABILITY,
      ),
      eq(ExecutionFailedAggregatedFields.STATE_IS_RETRYABLE, true),
      lte(ExecutionFailedAggregatedFields.STATE_RETRY_STATE_NEXT_RETRY_AT, now),
      or(
        eq(
          ExecutionFailedAggregatedFields.STATE_STATUS,
          ExecutionFailedStatus.FAILED,
        ),
        and(
          eq(
            ExecutionFailedAggregatedFields.STATE_STATUS,
            ExecutionFailedStatus.PREPARED,
          ),
          lte(
            ExecutionFailedAggregatedFields.STATE_RETRY_STATE_TIMEOUT_AT,
            now,
          ),
        ),
      ),
    );
  }

  static nonRetryableCondition = and(
    isIn(
      ExecutionFailedAggregatedFields.STATE_RECOVERABLE,
      ...RETRYABLE_RECOVERABILITY,
    ),
    isIn(ExecutionFailedAggregatedFields.STATE_STATUS, ...ACTIVE_STATUSES),
    eq(ExecutionFailedAggregatedFields.STATE_IS_BELOW_RETRY_THRESHOLD, false),
  );

  static successCondition = eq(
    ExecutionFailedAggregatedFields.STATE_STATUS,
    ExecutionFailedStatus.SUCCEEDED,
  );

  static unrecoverableCondition = and(
    eq(
      ExecutionFailedAggregatedFields.STATE_RECOVERABLE,
      RecoverableType.UNRECOVERABLE,
    ),
    isIn(ExecutionFailedAggregatedFields.STATE_STATUS, ...ACTIVE_STATUSES),
  );

  static categoryToCondition(category: FindCategory, now: number): Condition {
    switch (category) {
      case FindCategory.ToRetry:
        return this.toRetryCondition(now);
      case FindCategory.Executing:
        return this.executingCondition(now);
      case FindCategory.NextRetry:
        return this.nextRetryCondition(now);
      case FindCategory.NonRetryable:
        return this.nonRetryableCondition;
      case FindCategory.Succeeded:
        return this.successCondition;
      case FindCategory.Unrecoverable:
        return this.unrecoverableCondition;
    }
  }
}
