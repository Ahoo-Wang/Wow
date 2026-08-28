import { filter, RecoverableType } from "@ahoo-wang/fetcher-wow";
import { describe, expect, it } from "vitest";
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
    ] as const;
    const activeStatuses = [
      ExecutionFailedStatus.FAILED,
      ExecutionFailedStatus.PREPARED,
    ] as const;

    it("uses index-friendly recoverability values for to-retry", () => {
      expect(RetryConditions.toRetryCondition(currentTime)).toEqual(
        filter.and([
          filter.isIn(
            ExecutionFailedAggregatedFields.STATE_RECOVERABLE,
            retryableRecoverability,
          ),
          filter.eq(ExecutionFailedAggregatedFields.STATE_IS_RETRYABLE, true),
          filter.or([
            filter.eq(
              ExecutionFailedAggregatedFields.STATE_STATUS,
              ExecutionFailedStatus.FAILED,
            ),
            filter.and([
              filter.eq(
                ExecutionFailedAggregatedFields.STATE_STATUS,
                ExecutionFailedStatus.PREPARED,
              ),
              filter.lte(
                ExecutionFailedAggregatedFields.STATE_RETRY_STATE_TIMEOUT_AT,
                currentTime,
              ),
            ]),
          ]),
        ]),
      );
    });

    it("keeps the executing range condition", () => {
      expect(RetryConditions.executingCondition(currentTime)).toEqual(
        filter.and([
          filter.eq(
            ExecutionFailedAggregatedFields.STATE_STATUS,
            ExecutionFailedStatus.PREPARED,
          ),
          filter.gt(
            ExecutionFailedAggregatedFields.STATE_RETRY_STATE_TIMEOUT_AT,
            currentTime,
          ),
        ]),
      );
    });

    it("uses index-friendly recoverability values for next-retry", () => {
      expect(RetryConditions.nextRetryCondition(currentTime)).toEqual(
        filter.and([
          filter.isIn(
            ExecutionFailedAggregatedFields.STATE_RECOVERABLE,
            retryableRecoverability,
          ),
          filter.eq(ExecutionFailedAggregatedFields.STATE_IS_RETRYABLE, true),
          filter.lte(
            ExecutionFailedAggregatedFields.STATE_RETRY_STATE_NEXT_RETRY_AT,
            currentTime,
          ),
          filter.or([
            filter.eq(
              ExecutionFailedAggregatedFields.STATE_STATUS,
              ExecutionFailedStatus.FAILED,
            ),
            filter.and([
              filter.eq(
                ExecutionFailedAggregatedFields.STATE_STATUS,
                ExecutionFailedStatus.PREPARED,
              ),
              filter.lte(
                ExecutionFailedAggregatedFields.STATE_RETRY_STATE_TIMEOUT_AT,
                currentTime,
              ),
            ]),
          ]),
        ]),
      );
    });

    it("uses closed enum values for non-retryable", () => {
      expect(RetryConditions.nonRetryableCondition).toEqual(
        filter.and([
          filter.isIn(
            ExecutionFailedAggregatedFields.STATE_RECOVERABLE,
            retryableRecoverability,
          ),
          filter.isIn(
            ExecutionFailedAggregatedFields.STATE_STATUS,
            activeStatuses,
          ),
          filter.eq(
            ExecutionFailedAggregatedFields.STATE_IS_BELOW_RETRY_THRESHOLD,
            false,
          ),
        ]),
      );
    });

    it("successCondition is defined", () => {
      expect(RetryConditions.successCondition).toBeDefined();
    });

    it("uses active status values for unrecoverable", () => {
      expect(RetryConditions.unrecoverableCondition).toEqual(
        filter.and([
          filter.eq(
            ExecutionFailedAggregatedFields.STATE_RECOVERABLE,
            RecoverableType.UNRECOVERABLE,
          ),
          filter.isIn(
            ExecutionFailedAggregatedFields.STATE_STATUS,
            activeStatuses,
          ),
        ]),
      );
    });
  });
});
