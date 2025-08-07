import "@ant-design/v5-patch-for-react-19";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import App from "./App.tsx";
import { FailedView } from "./components/FailedView.tsx";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
    <FailedView></FailedView>
  </StrictMode>,
);
