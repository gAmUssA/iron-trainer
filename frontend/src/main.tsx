import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter, Routes, Route } from "react-router-dom";
import App from "./App";
import { AdminApp } from "./admin/AdminApp";
import "./styles.css";
import { ThemeProvider } from "./theme";
import { UnitsProvider } from "./units";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ThemeProvider>
      <UnitsProvider>
        <BrowserRouter>
          <Routes>
            {/* Password-gated ops console, separate from the athlete app (bean gfb3). */}
            <Route path="/admin/*" element={<AdminApp />} />
            <Route path="/*" element={<App />} />
          </Routes>
        </BrowserRouter>
      </UnitsProvider>
    </ThemeProvider>
  </React.StrictMode>
);
