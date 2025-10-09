import React from "react";
import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import { GlobalDrawerProvider } from "../GlobalDrawer.tsx";
import { useGlobalDrawer } from "../useGlobalDrawer.ts";

// Mock antd Drawer
vi.mock("antd", () => ({
  Drawer: ({ open, onClose, children }: { open?: boolean; onClose?: () => void; children?: React.ReactNode }) => (
    <div data-testid="drawer" data-open={open}>
      {children}
      <button onClick={onClose} data-testid="close-button">Close</button>
    </div>
  ),
}));

function TestComponent() {
  const { openDrawer, closeDrawer } = useGlobalDrawer();

  return (
    <div>
      <button onClick={() => openDrawer({ title: "Test", children: <div>Test Content</div> })}>
        Open Drawer
      </button>
      <button onClick={closeDrawer}>Close Drawer</button>
    </div>
  );
}

describe("GlobalDrawerProvider", () => {
  it("provides context to children", () => {
    render(
      <GlobalDrawerProvider>
        <TestComponent />
      </GlobalDrawerProvider>
    );

    expect(screen.getByText("Open Drawer")).toBeInTheDocument();
    expect(screen.getByText("Close Drawer")).toBeInTheDocument();
  });

  it("opens drawer when openDrawer is called", () => {
    render(
      <GlobalDrawerProvider>
        <TestComponent />
      </GlobalDrawerProvider>
    );

    const openButton = screen.getByText("Open Drawer");
    fireEvent.click(openButton);

    const drawer = screen.getByTestId("drawer");
    expect(drawer).toHaveAttribute("data-open", "true");
  });

  it("closes drawer when closeDrawer is called", () => {
    render(
      <GlobalDrawerProvider>
        <TestComponent />
      </GlobalDrawerProvider>
    );

    const openButton = screen.getByText("Open Drawer");
    fireEvent.click(openButton);

    const closeButton = screen.getByText("Close Drawer");
    fireEvent.click(closeButton);

    const drawer = screen.getByTestId("drawer");
    expect(drawer).toHaveAttribute("data-open", "false");
  });

  it("closes drawer when drawer close button is clicked", () => {
    render(
      <GlobalDrawerProvider>
        <TestComponent />
      </GlobalDrawerProvider>
    );

    const openButton = screen.getByText("Open Drawer");
    fireEvent.click(openButton);

    const drawerCloseButton = screen.getByTestId("close-button");
    fireEvent.click(drawerCloseButton);

    const drawer = screen.getByTestId("drawer");
    expect(drawer).toHaveAttribute("data-open", "false");
  });
});