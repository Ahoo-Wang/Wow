import { describe, it, expect, vi, beforeEach, afterAll } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
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
    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Try again" }),
    ).toBeInTheDocument();
  });

  it("lets the user retry rendering after a transient failure", () => {
    let shouldThrow = true;
    const SometimesFails = () => {
      if (shouldThrow) {
        throw new Error("Transient error");
      }
      return <div>Recovered content</div>;
    };

    render(
      <ErrorBoundary>
        <SometimesFails />
      </ErrorBoundary>,
    );

    shouldThrow = false;
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));

    expect(screen.getByText("Recovered content")).toBeInTheDocument();
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
