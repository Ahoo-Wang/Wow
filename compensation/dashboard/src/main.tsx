import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import "@ahoo-wang/wow-view-engine/styles.css";
import "@ahoo-wang/wow-view-engine/themes/porcelain.css";
import { RouterProvider } from "react-router";
import { AppRouter } from "./routes/Routes.tsx";
import "./services/compensationFetcher";
import { TooltipProvider } from "@/components/ui/tooltip";
import { I18nProvider } from "@/i18n.tsx";
import { applyColorMode, readColorMode } from "@/features/App/colorMode.ts";

const rootElement = document.getElementById("root");
if (!rootElement) {
  throw new Error("Dashboard root element #root was not found");
}

// Painted before the first render, so a dark system never flashes light.
applyColorMode(readColorMode());

createRoot(rootElement).render(
  <StrictMode>
    <I18nProvider>
      <TooltipProvider>
        <RouterProvider router={AppRouter} />
      </TooltipProvider>
    </I18nProvider>
  </StrictMode>,
);
