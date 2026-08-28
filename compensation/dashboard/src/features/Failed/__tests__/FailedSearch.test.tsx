import { fireEvent, render, screen } from "@testing-library/react";
import { filter } from "@ahoo-wang/fetcher-wow";
import { describe, expect, it, vi } from "vitest";
import { FailedSearch } from "../FailedSearch.tsx";

describe("FailedSearch", () => {
  it("prevents duplicate submissions while a search is in flight", () => {
    render(<FailedSearch loading />);

    expect(screen.getByRole("form")).toHaveAttribute("aria-busy", "true");
    expect(screen.getByRole("button", { name: "Search" })).toBeDisabled();
  });

  it("removes an active filter and clears its value", () => {
    const onSearch = vi.fn();
    render(<FailedSearch onSearch={onSearch} />);

    fireEvent.click(screen.getByRole("button", { name: "Add filter" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Event ID" }));
    fireEvent.change(screen.getByRole("textbox", { name: "Event ID" }), {
      target: { value: "EVT-1" },
    });

    fireEvent.click(screen.getByRole("button", { name: "Remove Event ID" }));

    expect(
      screen.queryByRole("textbox", { name: "Event ID" }),
    ).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Search" }));
    expect(onSearch).toHaveBeenCalledWith(filter.matchAll(), false);
  });

  it("preserves the legacy exact-match semantics for text filters", () => {
    const onSearch = vi.fn();
    render(<FailedSearch onSearch={onSearch} />);

    fireEvent.change(screen.getByRole("textbox", { name: "Execution ID" }), {
      target: { value: "EXC-1" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add filter" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Event ID" }));
    fireEvent.change(screen.getByRole("textbox", { name: "Event ID" }), {
      target: { value: "EVT-1" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Search" }));

    expect(onSearch).toHaveBeenCalledWith(
      filter.and([filter.id("EXC-1"), filter.eq("state.eventId.id", "EVT-1")]),
      true,
    );
  });

  it("clears every filter and immediately restores the unfiltered result", () => {
    const onSearch = vi.fn();
    render(<FailedSearch onSearch={onSearch} />);

    fireEvent.change(screen.getByRole("textbox", { name: "Execution ID" }), {
      target: { value: "EXC-1" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add filter" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Event ID" }));
    fireEvent.change(screen.getByRole("textbox", { name: "Event ID" }), {
      target: { value: "EVT-1" },
    });

    expect(
      screen.getByRole("button", { name: "Clear all filters" }),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Clear all filters" }));

    expect(screen.getByRole("textbox", { name: "Execution ID" })).toHaveValue(
      "",
    );
    expect(
      screen.queryByRole("textbox", { name: "Event ID" }),
    ).not.toBeInTheDocument();
    expect(onSearch).toHaveBeenCalledWith(filter.matchAll(), false);
  });
});
