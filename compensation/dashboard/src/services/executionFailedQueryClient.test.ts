import { beforeEach, describe, expect, it, vi } from "vitest";
import { aggregateId, pagedQuery, singleQuery } from "@ahoo-wang/fetcher-wow";
import {
  queryExecutionFailedPage,
  queryExecutionFailedState,
} from "./executionFailedQueryClient.ts";

const mocks = vi.hoisted(() => ({
  createSnapshotQueryClient: vi.fn(),
  pagedState: vi.fn(),
  singleState: vi.fn(),
}));

vi.mock("../generated", () => ({
  executionFailedQueryClientFactory: {
    createSnapshotQueryClient: mocks.createSnapshotQueryClient.mockReturnValue({
      pagedState: mocks.pagedState,
      singleState: mocks.singleState,
    }),
  },
}));

describe("executionFailedQueryClient", () => {
  beforeEach(() => {
    mocks.pagedState.mockClear();
    mocks.singleState.mockClear();
  });

  it("creates the generated client behind the dashboard service boundary", () => {
    expect(mocks.createSnapshotQueryClient).toHaveBeenCalledWith({
      contextAlias: "",
    });
  });

  it("delegates paged queries with request context and cancellation", async () => {
    const query = pagedQuery({ condition: aggregateId("failed-1") });
    const attributes = { source: "dashboard" };
    const abortController = new AbortController();

    await queryExecutionFailedPage(query, attributes, abortController);

    expect(mocks.pagedState).toHaveBeenCalledWith(
      query,
      attributes,
      abortController,
    );
  });

  it("delegates single-state queries with request context and cancellation", async () => {
    const query = singleQuery({ condition: aggregateId("failed-1") });
    const attributes = { source: "dashboard" };
    const abortController = new AbortController();

    await queryExecutionFailedState(query, attributes, abortController);

    expect(mocks.singleState).toHaveBeenCalledWith(
      query,
      attributes,
      abortController,
    );
  });
});
