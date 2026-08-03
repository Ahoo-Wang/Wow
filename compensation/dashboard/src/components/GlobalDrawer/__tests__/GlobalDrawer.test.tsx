import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { GlobalDrawerProvider } from "../GlobalDrawer.tsx";
import { useGlobalDrawer } from "../useGlobalDrawer.ts";

function TestComponent() {
  const { openDrawer, closeDrawer } = useGlobalDrawer();
  return (
    <div>
      <button
        onClick={() =>
          openDrawer({
            title: "Test drawer",
            children: <div>Test content</div>,
          })
        }
      >
        Open drawer
      </button>
      <button onClick={closeDrawer}>Close from context</button>
      <button
        onClick={() =>
          openDrawer({
            title: "Responsive drawer",
            children: <div>Responsive content</div>,
            width: 500,
          })
        }
      >
        Open responsive drawer
      </button>
    </div>
  );
}

describe("GlobalDrawerProvider", () => {
  it("opens the neutral sheet context", () => {
    render(
      <GlobalDrawerProvider>
        <TestComponent />
      </GlobalDrawerProvider>,
    );

    fireEvent.click(screen.getByRole("button", { name: "Open drawer" }));
    expect(
      screen.getByRole("dialog", { name: "Test drawer" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Test content")).toBeInTheDocument();
  });

  it("closes from the sheet close button", () => {
    render(
      <GlobalDrawerProvider>
        <TestComponent />
      </GlobalDrawerProvider>,
    );

    fireEvent.click(screen.getByRole("button", { name: "Open drawer" }));
    fireEvent.click(screen.getByRole("button", { name: "Close" }));

    expect(
      screen.queryByRole("dialog", { name: "Test drawer" }),
    ).not.toBeInTheDocument();
  });

  it("caps requested widths to the mobile viewport", () => {
    render(
      <GlobalDrawerProvider>
        <TestComponent />
      </GlobalDrawerProvider>,
    );

    fireEvent.click(
      screen.getByRole("button", { name: "Open responsive drawer" }),
    );

    expect(
      screen.getByRole("dialog", { name: "Responsive drawer" }),
    ).toHaveStyle({ width: "500px", maxWidth: "92vw" });
  });
});
