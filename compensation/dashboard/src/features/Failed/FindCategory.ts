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
} from "../../services";

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
      ne(ExecutionFailedAggregatedFields["state.recoverable"], RecoverableType.UNRECOVERABLE),
      eq(ExecutionFailedAggregatedFields["state.isRetryable"], true),
      or(
        eq(ExecutionFailedAggregatedFields["state.status"], ExecutionFailedStatus.FAILED),
        and(
          eq(ExecutionFailedAggregatedFields["state.status"], ExecutionFailedStatus.PREPARED),
          lte(ExecutionFailedAggregatedFields["state.retryState.timeoutAt"], new Date().getTime()),
        ),
      ),
    );
  }

  static executingCondition(): Condition {
    return and(
      eq(ExecutionFailedAggregatedFields["state.status"], ExecutionFailedStatus.PREPARED),
      gt(ExecutionFailedAggregatedFields["state.retryState.timeoutAt"], new Date().getTime()),
    );
  }

  static nextRetryCondition(): Condition {
    let currentTime = new Date().getTime();
    return and(
      ne(ExecutionFailedAggregatedFields["state.recoverable"], RecoverableType.UNRECOVERABLE),
      eq(ExecutionFailedAggregatedFields["state.isRetryable"], true),
      lte(ExecutionFailedAggregatedFields["state.retryState.nextRetryAt"], currentTime),
      or(
        eq(ExecutionFailedAggregatedFields["state.status"], ExecutionFailedStatus.FAILED),
        and(
          eq(ExecutionFailedAggregatedFields["state.status"], ExecutionFailedStatus.PREPARED),
          lte(ExecutionFailedAggregatedFields["state.retryState.timeoutAt"], currentTime),
        ),
      ),
    );
  }

  static nonRetryableCondition = and(
    ne(ExecutionFailedAggregatedFields["state.recoverable"], RecoverableType.UNRECOVERABLE),
    ne(ExecutionFailedAggregatedFields["state.status"], ExecutionFailedStatus.SUCCEEDED),
    eq(ExecutionFailedAggregatedFields["state.isBelowRetryThreshold"], false),
  );

  static successCondition = eq(
    ExecutionFailedAggregatedFields["state.status"],
    ExecutionFailedStatus.SUCCEEDED,
  );
  static unrecoverableCondition = and(
    eq(ExecutionFailedAggregatedFields["state.recoverable"], RecoverableType.UNRECOVERABLE),
    ne(ExecutionFailedAggregatedFields["state.status"], ExecutionFailedStatus.SUCCEEDED),
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
