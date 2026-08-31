import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import { RouterProvider } from "react-router";
import { AppRouter } from "./routes/Routes.tsx";
import { GlobalDrawerProvider } from "./components/GlobalDrawer";
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
        <GlobalDrawerProvider>
          <RouterProvider router={AppRouter} />
          <Toaster position="bottom-right" richColors />
        </GlobalDrawerProvider>
      </TooltipProvider>
    </I18nProvider>
  </StrictMode>,
);
