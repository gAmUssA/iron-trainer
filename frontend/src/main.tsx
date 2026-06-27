import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App";
import "./styles.css";
import { ThemeProvider } from "./theme";
import { UnitsProvider } from "./units";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ThemeProvider>
      <UnitsProvider>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </UnitsProvider>
    </ThemeProvider>
  </React.StrictMode>
);
