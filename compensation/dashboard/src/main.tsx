import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import { RouterProvider } from "react-router";
import { AppRouter } from "./routes/Routes.tsx";
import { GlobalDrawerProvider } from "./components/GlobalDrawer";
import "./services/compensationFetcher";
import { TooltipProvider } from "@/components/ui/tooltip";
import { Toaster } from "@/components/ui/sonner";

const rootElement = document.getElementById("root");
if (!rootElement) {
  throw new Error("Dashboard root element #root was not found");
}

createRoot(rootElement).render(
  <StrictMode>
    <TooltipProvider>
      <GlobalDrawerProvider>
        <RouterProvider router={AppRouter} />
        <Toaster position="bottom-right" richColors />
      </GlobalDrawerProvider>
    </TooltipProvider>
  </StrictMode>,
);
