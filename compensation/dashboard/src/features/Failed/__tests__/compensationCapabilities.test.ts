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
  it("disables preparation while the execution timeout is active", () => {
    expect(getCompensationCapabilities(preparedState, 1_999)).toEqual({
      canForcePrepare: false,
      canPrepare: false,
      unavailableReason: "Compensation is currently executing.",
    });
  });

  it("enables preparation after the browser clock passes the timeout", () => {
    expect(getCompensationCapabilities(preparedState, 2_001)).toEqual({
      canForcePrepare: true,
      canPrepare: true,
    });
  });
});
