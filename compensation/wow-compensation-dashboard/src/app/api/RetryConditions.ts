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

import {Condition, Conditions} from "./Query";
import {ExecutionFailedStatus, RecoverableType} from "./ExecutionFailedState";
import {FindCategory} from "./CompensationClient";

export enum ExecutionFailedFields {
  STATUS = "state.status",
  RECOVERABLE = "state.recoverable",
  IS_RETRYABLE = "state.isRetryable",
  IS_BELOW_RETRY_THRESHOLD = "state.isBelowRetryThreshold",
  NEXT_RETRY_AT = "state.retryState.nextRetryAt",
  TIMEOUT_AT = "state.retryState.timeoutAt",
}

export class RetryConditions {

  static allCondition = Conditions.empty()

  static toRetryCondition(): Condition {
    return Conditions.and(
      [
        Conditions.ne(ExecutionFailedFields.RECOVERABLE, RecoverableType.UNRECOVERABLE),
        Conditions.eq(ExecutionFailedFields.IS_RETRYABLE, true),
        Conditions.or([
          Conditions.eq(ExecutionFailedFields.STATUS, ExecutionFailedStatus.FAILED),
          Conditions.and([
            Conditions.eq(ExecutionFailedFields.STATUS, ExecutionFailedStatus.PREPARED),
            Conditions.lte(ExecutionFailedFields.TIMEOUT_AT, new Date().getTime()),
          ])
        ])
      ]
    )
  }

  static executingCondition(): Condition {
    return Conditions.and([
      Conditions.eq(ExecutionFailedFields.STATUS, ExecutionFailedStatus.PREPARED),
      Conditions.gt(ExecutionFailedFields.TIMEOUT_AT, new Date().getTime())
    ])
  }

  static nextRetryCondition(): Condition {
    let currentTime = new Date().getTime()
    return Conditions.and(
      [
        Conditions.ne(ExecutionFailedFields.RECOVERABLE, RecoverableType.UNRECOVERABLE),
        Conditions.eq(ExecutionFailedFields.IS_RETRYABLE, true),
        Conditions.lte(ExecutionFailedFields.NEXT_RETRY_AT, currentTime),
        Conditions.or([
          Conditions.eq(ExecutionFailedFields.STATUS, ExecutionFailedStatus.FAILED),
          Conditions.and([
            Conditions.eq(ExecutionFailedFields.STATUS, ExecutionFailedStatus.PREPARED),
            Conditions.lte(ExecutionFailedFields.TIMEOUT_AT, currentTime),
          ])
        ])
      ]
    )
  }

  static nonRetryableCondition = Conditions.and(
    [
      Conditions.ne(ExecutionFailedFields.RECOVERABLE, RecoverableType.UNRECOVERABLE),
      Conditions.ne(ExecutionFailedFields.STATUS, ExecutionFailedStatus.SUCCEEDED),
      Conditions.eq(ExecutionFailedFields.IS_BELOW_RETRY_THRESHOLD, false)
    ]
  )

  static successCondition = Conditions.eq(ExecutionFailedFields.STATUS, ExecutionFailedStatus.SUCCEEDED)
  static unrecoverableCondition = Conditions.eq(ExecutionFailedFields.RECOVERABLE, RecoverableType.UNRECOVERABLE)

  static categoryToCondition(category: FindCategory): Condition {
    switch (category) {
      case FindCategory.ALL:
        return this.allCondition
      case FindCategory.TO_RETRY:
        return this.toRetryCondition()
      case FindCategory.EXECUTING:
        return this.executingCondition()
      case FindCategory.NEXT_RETRY:
        return this.nextRetryCondition()
      case FindCategory.NON_RETRYABLE:
        return this.nonRetryableCondition
      case FindCategory.SUCCESS:
        return this.successCondition
      case FindCategory.UNRECOVERABLE:
        return this.unrecoverableCondition
    }
  }
}
