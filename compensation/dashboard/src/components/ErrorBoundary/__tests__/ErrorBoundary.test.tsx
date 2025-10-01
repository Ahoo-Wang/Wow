import { describe, it, expect, vi, beforeEach, afterAll } from "vitest";
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { ErrorBoundary } from "../ErrorBoundary.tsx";

// Mock console.error
const consoleErrorSpy = vi.spyOn(console, "error").mockImplementation(() => {});

describe("ErrorBoundary", () => {
  beforeEach(() => {
    consoleErrorSpy.mockClear();
  });

  afterAll(() => {
    consoleErrorSpy.mockRestore();
  });

  it("renders children when no error", () => {
    render(
      <ErrorBoundary>
        <div>Test Child</div>
      </ErrorBoundary>,
    );

    expect(screen.getByText("Test Child")).toBeInTheDocument();
  });

  it("renders error UI when error occurs", () => {
    const ThrowError = () => {
      throw new Error("Test error");
    };

    render(
      <ErrorBoundary>
        <ThrowError />
      </ErrorBoundary>,
    );

    expect(screen.getByText("Something went wrong.")).toBeInTheDocument();
    expect(screen.getByText("Error: Test error")).toBeInTheDocument();
  });

  it("calls componentDidCatch when error occurs", () => {
    const ThrowError = () => {
      throw new Error("Test error");
    };

    render(
      <ErrorBoundary>
        <ThrowError />
      </ErrorBoundary>,
    );

    expect(consoleErrorSpy).toHaveBeenCalledWith(
      "Error caught by error boundary:",
      expect.any(Error),
      expect.any(Object),
    );
  });
});
