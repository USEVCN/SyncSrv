import { App as AntApp, ConfigProvider, Spin } from "antd";
import zhCN from "antd/locale/zh_CN";
import dayjs from "dayjs";
import "dayjs/locale/zh-cn";
import { useEffect, useState } from "react";
import { BrowserRouter } from "react-router-dom";
import { darkTheme } from "../shared/theme";
import { api } from "../platform/http";
import { Login } from "../modules/auth/Login";
import { StateProvider } from "./State";
import { AppShell } from "./AppShell";
import { SystemAppearance } from "../modules/settings/SystemAppearance";

dayjs.locale("zh-cn");
export default function App() {
  const [loggedIn, setLoggedIn] = useState<boolean | undefined>();
  useEffect(() => {
    api("/auth/me")
      .then(() => setLoggedIn(true))
      .catch(() => setLoggedIn(false));
    const logout = () => setLoggedIn(false);
    window.addEventListener("syncsrv:unauthorized", logout);
    return () => window.removeEventListener("syncsrv:unauthorized", logout);
  }, []);
  return (
    <ConfigProvider theme={darkTheme} locale={zhCN}>
      <AntApp>
        {loggedIn === undefined ? (
          <div className="loading-page">
            <Spin />
          </div>
        ) : loggedIn ? (
          <BrowserRouter>
            <StateProvider>
              <SystemAppearance><AppShell onLogout={() => setLoggedIn(false)} /></SystemAppearance>
            </StateProvider>
          </BrowserRouter>
        ) : (
          <Login onLogin={() => setLoggedIn(true)} />
        )}
      </AntApp>
    </ConfigProvider>
  );
}
