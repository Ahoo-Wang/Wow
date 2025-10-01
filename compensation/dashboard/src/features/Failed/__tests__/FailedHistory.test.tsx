import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { FailedHistory } from "../FailedHistory.tsx";

describe("FailedHistory", () => {
  it("renders FailedHistory text", () => {
    render(<FailedHistory />);

    expect(screen.getByText("FailedHistory")).toBeInTheDocument();
  });
});