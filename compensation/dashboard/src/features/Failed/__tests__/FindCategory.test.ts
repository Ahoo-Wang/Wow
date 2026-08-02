import {
  and,
  eq,
  gt,
  isIn,
  lte,
  or,
  RecoverableType,
} from "@ahoo-wang/fetcher-wow";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  ExecutionFailedAggregatedFields,
  ExecutionFailedStatus,
} from "../../../generated";
import { FindCategory } from "../FindCategory.ts";
import { RetryConditions } from "../RetryConditions.ts";

describe("FindCategory", () => {
  describe("enum values", () => {
    it("has correct enum values", () => {
      expect(FindCategory.ToRetry).toBe("ToRetry");
      expect(FindCategory.Executing).toBe("Executing");
      expect(FindCategory.NextRetry).toBe("NextRetry");
      expect(FindCategory.NonRetryable).toBe("NonRetryable");
      expect(FindCategory.Succeeded).toBe("Succeeded");
      expect(FindCategory.Unrecoverable).toBe("Unrecoverable");
    });
  });

  describe("RetryConditions", () => {
    const currentTime = 1785501209222;
    const retryableRecoverability = [
      RecoverableType.RECOVERABLE,
      RecoverableType.UNKNOWN,
    ];
    const activeStatuses = [
      ExecutionFailedStatus.FAILED,
      ExecutionFailedStatus.PREPARED,
    ];

    beforeEach(() => {
      vi.useFakeTimers();
      vi.setSystemTime(currentTime);
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it("uses index-friendly recoverability values for to-retry", () => {
      expect(RetryConditions.toRetryCondition()).toEqual(
        and(
          isIn(
            ExecutionFailedAggregatedFields.STATE_RECOVERABLE,
            ...retryableRecoverability,
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
                currentTime,
              ),
            ),
          ),
        ),
      );
    });

    it("keeps the executing range condition", () => {
      expect(RetryConditions.executingCondition()).toEqual(
        and(
          eq(
            ExecutionFailedAggregatedFields.STATE_STATUS,
            ExecutionFailedStatus.PREPARED,
          ),
          gt(
            ExecutionFailedAggregatedFields.STATE_RETRY_STATE_TIMEOUT_AT,
            currentTime,
          ),
        ),
      );
    });

    it("uses index-friendly recoverability values for next-retry", () => {
      expect(RetryConditions.nextRetryCondition()).toEqual(
        and(
          isIn(
            ExecutionFailedAggregatedFields.STATE_RECOVERABLE,
            ...retryableRecoverability,
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
        ),
      );
    });

    it("uses closed enum values for non-retryable", () => {
      expect(RetryConditions.nonRetryableCondition).toEqual(
        and(
          isIn(
            ExecutionFailedAggregatedFields.STATE_RECOVERABLE,
            ...retryableRecoverability,
          ),
          isIn(ExecutionFailedAggregatedFields.STATE_STATUS, ...activeStatuses),
          eq(
            ExecutionFailedAggregatedFields.STATE_IS_BELOW_RETRY_THRESHOLD,
            false,
          ),
        ),
      );
    });

    it("successCondition is defined", () => {
      expect(RetryConditions.successCondition).toBeDefined();
    });

    it("uses active status values for unrecoverable", () => {
      expect(RetryConditions.unrecoverableCondition).toEqual(
        and(
          eq(
            ExecutionFailedAggregatedFields.STATE_RECOVERABLE,
            RecoverableType.UNRECOVERABLE,
          ),
          isIn(ExecutionFailedAggregatedFields.STATE_STATUS, ...activeStatuses),
        ),
      );
    });
  });
});
