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
  all,
  and,
  type Condition,
  eq,
  gt,
  lte,
  ne,
  or,
  RecoverableType,
} from "@ahoo-wang/fetcher-wow";
import {
  ExecutionFailedAggregatedFields,
  ExecutionFailedStatus,
} from "../../generated";

export enum FindCategory {
  All = "All",
  ToRetry = "ToRetry",
  Executing = "Executing",
  NextRetry = "NextRetry",
  NonRetryable = "NonRetryable",
  Succeeded = "Succeeded",
  Unrecoverable = "Unrecoverable",
}

export class RetryConditions {
  static toRetryCondition(): Condition {
    return and(
      ne(
        ExecutionFailedAggregatedFields.STATE_RECOVERABLE,
        RecoverableType.UNRECOVERABLE,
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
            new Date().getTime(),
          ),
        ),
      ),
    );
  }

  static executingCondition(): Condition {
    return and(
      eq(
        ExecutionFailedAggregatedFields.STATE_STATUS,
        ExecutionFailedStatus.PREPARED,
      ),
      gt(
        ExecutionFailedAggregatedFields.STATE_RETRY_STATE_TIMEOUT_AT,
        new Date().getTime(),
      ),
    );
  }

  static nextRetryCondition(): Condition {
    let currentTime = new Date().getTime();
    return and(
      ne(
        ExecutionFailedAggregatedFields.STATE_RECOVERABLE,
        RecoverableType.UNRECOVERABLE,
      ),
      eq(ExecutionFailedAggregatedFields.STATE_IS_RETRYABLE, true),
      lte(
        ExecutionFailedAggregatedFields.STATE_RETRY_STATE_NEXT_RETRY_AT,
        currentTime,
      ),
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
            currentTime,
          ),
        ),
      ),
    );
  }

  static nonRetryableCondition = and(
    ne(
      ExecutionFailedAggregatedFields.STATE_RECOVERABLE,
      RecoverableType.UNRECOVERABLE,
    ),
    ne(
      ExecutionFailedAggregatedFields.STATE_STATUS,
      ExecutionFailedStatus.SUCCEEDED,
    ),
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
    ne(
      ExecutionFailedAggregatedFields.STATE_STATUS,
      ExecutionFailedStatus.SUCCEEDED,
    ),
  );

  static categoryToCondition(category: FindCategory): Condition {
    switch (category) {
      case FindCategory.All:
        return all();
      case FindCategory.ToRetry:
        return this.toRetryCondition();
      case FindCategory.Executing:
        return this.executingCondition();
      case FindCategory.NextRetry:
        return this.nextRetryCondition();
      case FindCategory.NonRetryable:
        return this.nonRetryableCondition;
      case FindCategory.Succeeded:
        return this.successCondition;
      case FindCategory.Unrecoverable:
        return this.unrecoverableCondition;
    }
  }
}
