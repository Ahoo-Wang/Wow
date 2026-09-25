import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
// After the console's own Tailwind: both stylesheets fill the same
// `utilities` layer, where the later rule wins. The engine's rules are scoped
// to its own surfaces, so coming last they keep its responsive variants
// (`md:w-64`, `md:flex-row`) over the console's global `.w-full` and
// `.flex-col` without touching the console's own markup.
import "@ahoo-wang/wow-view-engine/styles.css";
import "@ahoo-wang/wow-view-engine/shadcn-bridge.css";
import { RouterProvider } from "react-router";
import { AppRouter } from "./routes/Routes.tsx";
import "./services/compensationFetcher";
import { TooltipProvider } from "@/components/ui/tooltip";
import { Toaster } from "@/components/ui/sonner";
import { I18nProvider } from "@/i18n.tsx";

const rootElement = document.getElementById("root");
if (!rootElement) {
  throw new Error("Dashboard root element #root was not found");
}

createRoot(rootElement).render(
  <StrictMode>
    <I18nProvider>
      <TooltipProvider>
        <RouterProvider router={AppRouter} />
        <Toaster position="bottom-right" richColors />
      </TooltipProvider>
    </I18nProvider>
  </StrictMode>,
);
