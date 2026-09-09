import { describe, expect, it } from "vitest";
import { FunctionKind, RecoverableType } from "@ahoo-wang/fetcher-wow";
import {
  ExecutionFailedStatus,
  type ExecutionFailedState,
} from "../../../generated";
import { getCompensationCapabilities } from "../compensationCapabilities.ts";

const preparedState: ExecutionFailedState = {
  id: "failed-1",
  status: ExecutionFailedStatus.PREPARED,
  recoverable: RecoverableType.RECOVERABLE,
  error: {
    errorCode: "TEST_ERROR",
    errorMsg: "Test error",
    stackTrace: "stack trace",
    succeeded: false,
    bindingErrors: [],
  },
  eventId: {
    id: "event-1",
    version: 1,
    aggregateId: {
      aggregateName: "payment",
      contextName: "billing",
      aggregateId: "payment-1",
      tenantId: "tenant-alpha",
    },
  },
  executeAt: 1_000,
  function: {
    contextName: "billing",
    processorName: "billing-orchestrator",
    name: "onPaymentAuthorized",
    functionKind: FunctionKind.EVENT,
  },
  retrySpec: { maxRetries: 10, minBackoff: 180, executionTimeout: 120 },
  retryState: {
    nextRetryAt: 2_000,
    retries: 3,
    retryAt: 1_000,
    timeoutAt: 2_000,
  },
  isBelowRetryThreshold: true,
  isRetryable: true,
};

describe("getCompensationCapabilities", () => {
  it("allows prepared executions after their timeout", () => {
    expect(getCompensationCapabilities(preparedState)).toEqual({
      canForcePrepare: true,
      canPrepare: true,
    });
  });

  it("blocks prepared executions until strictly after their timeout", () => {
    for (const now of [1_999, 2_000]) {
      expect(getCompensationCapabilities(preparedState, now)).toMatchObject({
        canForcePrepare: false,
        canPrepare: false,
      });
    }
    expect(getCompensationCapabilities(preparedState, 2_001)).toMatchObject({
      canForcePrepare: true,
      canPrepare: true,
    });
  });

  it("disables commands that the succeeded state can never accept", () => {
    expect(
      getCompensationCapabilities({
        ...preparedState,
        status: ExecutionFailedStatus.SUCCEEDED,
      }),
    ).toEqual({
      canForcePrepare: false,
      canPrepare: false,
      unavailableReason: "This execution has already succeeded.",
    });
  });

  it("keeps only the server-side force path after the retry limit", () => {
    expect(
      getCompensationCapabilities({
        ...preparedState,
        isBelowRetryThreshold: false,
      }),
    ).toEqual({
      canForcePrepare: true,
      canPrepare: false,
      unavailableReason: "Retry limit reached; force prepare remains available.",
    });
  });
});
