import "@ant-design/v5-patch-for-react-19";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "antd/dist/reset.css";
import "./index.css";
import { RouterProvider } from "react-router";
import { AppRouter } from "./routes/Routes.tsx";
import { App } from "antd";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App>
      <RouterProvider router={AppRouter} />
    </App>
  </StrictMode>,
);
