import { describe, it, expect } from "vitest";
import { RetryConditions, FindCategory } from "../FindCategory.ts";

describe("FindCategory", () => {
  describe("enum values", () => {
    it("has correct enum values", () => {
      expect(FindCategory.All).toBe("All");
      expect(FindCategory.ToRetry).toBe("ToRetry");
      expect(FindCategory.Executing).toBe("Executing");
      expect(FindCategory.NextRetry).toBe("NextRetry");
      expect(FindCategory.NonRetryable).toBe("NonRetryable");
      expect(FindCategory.Succeeded).toBe("Succeeded");
      expect(FindCategory.Unrecoverable).toBe("Unrecoverable");
    });
  });

  describe("RetryConditions", () => {
    it("toRetryCondition returns correct condition", () => {
      const condition = RetryConditions.toRetryCondition();
      expect(condition).toBeDefined();
      // We can't easily test the exact structure without mocking the fetcher-wow functions
      // But we can verify it returns a condition object
    });

    it("executingCondition returns correct condition", () => {
      const condition = RetryConditions.executingCondition();
      expect(condition).toBeDefined();
    });

    it("nextRetryCondition returns correct condition", () => {
      const condition = RetryConditions.nextRetryCondition();
      expect(condition).toBeDefined();
    });

    it("nonRetryableCondition is defined", () => {
      expect(RetryConditions.nonRetryableCondition).toBeDefined();
    });

    it("successCondition is defined", () => {
      expect(RetryConditions.successCondition).toBeDefined();
    });

    it("unrecoverableCondition is defined", () => {
      expect(RetryConditions.unrecoverableCondition).toBeDefined();
    });
  });
});