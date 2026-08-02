import type { ExchangeError } from "@ahoo-wang/fetcher";
import type { CommandResult } from "@ahoo-wang/fetcher-wow";
import { describe, expect, it, vi } from "vitest";
import { commandErrorMessage } from "../commandErrors.ts";

function exchangeError(
  extractResult: () => Promise<Partial<CommandResult>>,
): ExchangeError {
  return {
    message: "transport failed",
    exchange: { extractResult },
  } as unknown as ExchangeError;
}

describe("commandErrorMessage", () => {
  it("uses the command result when the response can be decoded", async () => {
    await expect(
      commandErrorMessage(
        exchangeError(() => Promise.resolve({ errorMsg: "command rejected" })),
      ),
    ).resolves.toBe("command rejected");
  });

  it("falls back to the transport error when decoding fails", async () => {
    const extractResult = vi.fn().mockRejectedValue(new Error("not json"));

    await expect(
      commandErrorMessage(exchangeError(extractResult)),
    ).resolves.toBe("transport failed");
  });
});
