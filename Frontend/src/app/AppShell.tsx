import { Hint } from "../shared/Hint";
import { Avatar, Button, Dropdown, Layout, Menu, Space, Spin, Tooltip, Typography } from "antd";
import { DownOutlined, LogoutOutlined, ReloadOutlined, SettingOutlined, UserOutlined } from "@ant-design/icons";
import { SidebarIcon } from "./SidebarIcon";
import { useSidebarCollapse } from "./useSidebarCollapse";
import "./AppShell.css";
import "./AppAmbient.css";
import "./SelectedMenuShine.css";
import { lazy, Suspense, useEffect, useState } from "react";
import {
  Navigate,
  Route,
  Routes,
  useLocation,
  useNavigate,
} from "react-router-dom";
import { useStateData } from "./State";
import { useAction } from "../shared/ui";
import { AccountSettingsModal } from "../modules/auth/AccountSettingsModal";
import { api } from "../platform/http";
const Dashboard = lazy(() =>
  import("../modules/dashboard/Dashboard").then((m) => ({
    default: m.Dashboard,
  })),
);
const StoragePage = lazy(() =>
  import("../modules/storage/StoragePage").then((m) => ({
    default: m.StoragePage,
  })),
);
const JobsPage = lazy(() =>
  import("../modules/jobs/JobsPage").then((m) => ({ default: m.JobsPage })),
);
const RunsPage = lazy(() =>
  import("../modules/jobs/RunsPage").then((m) => ({ default: m.RunsPage })),
);
const SnapshotsPage = lazy(() =>
  import("../modules/restore/SnapshotsPage").then((m) => ({
    default: m.SnapshotsPage,
  })),
);
const AuditPage = lazy(() =>
  import("../modules/audit/AuditPage").then((m) => ({ default: m.AuditPage })),
);
const SettingsPage = lazy(() =>
  import("../modules/settings/SettingsPage").then((m) => ({
    default: m.SettingsPage,
  })),
);

const AgentsPage = lazy(() => import("../modules/agents/AgentsPage").then(m => ({ default: m.AgentsPage })));

const navigation = [
  { key: "workspace", type: "group" as const, label: "备份工作区", children: [
    { key: "/dashboard", icon: <SidebarIcon name="overview" />, label: "服务总览" },
    { key: "/agents", icon: <SidebarIcon name="devices" />, label: "备份设备" },
    { key: "/storages", icon: <SidebarIcon name="storage" />, label: "存储连接" },
  ] },
  { key: "backup", type: "group" as const, label: "备份与恢复", children: [
    { key: "/jobs", icon: <SidebarIcon name="backup" />, label: "备份任务" },
    { key: "/runs", icon: <SidebarIcon name="records" />, label: "执行记录" },
    { key: "/snapshots", icon: <SidebarIcon name="restore" />, label: "恢复" },
  ] },
  { key: "management", type: "group" as const, label: "系统管理", children: [
    { key: "/audit", icon: <SidebarIcon name="audit" />, label: "审计中心" },
  ] },
];
export function AppShell({ onLogout }: { onLogout: () => void }) {
  const { data, error, refresh } = useStateData();
  const location = useLocation();
  const navigate = useNavigate();
  const [desktopCollapsed, setDesktopCollapsed] = useSidebarCollapse();
  const [compact, setCompact] = useState(() => window.innerWidth <= 600);
  const [compactExpanded, setCompactExpanded] = useState(false);
  const collapsed = compact ? !compactExpanded : desktopCollapsed;
  const toggleNavigation = () => compact ? setCompactExpanded(value => !value) : setDesktopCollapsed(value => !value);
  useEffect(() => {
    const resize = () => { const narrow = window.innerWidth <= 600; setCompact(narrow); if (!narrow) setCompactExpanded(false); };
    window.addEventListener("resize", resize);
    return () => window.removeEventListener("resize", resize);
  }, []);
  const settingsOpen = location.pathname === "/settings";
  const settingsBackground = (location.state as {settingsBackground?:string}|null)?.settingsBackground || "/dashboard";
  const canManageSettings = data?.currentUser?.role === "ADMIN";
  const openSettings = () => { if (canManageSettings) navigate("/settings", {state:{settingsBackground:settingsOpen?settingsBackground:location.pathname+location.search}}); };
  const navigationPath = settingsOpen ? settingsBackground.split(/[?#]/)[0] : location.pathname;
  const selectedKey=navigation.flatMap(group=>group.children).find(item=>navigationPath===item.key || navigationPath.startsWith(`${item.key}/`))?.key || "/dashboard";
  const action = useAction();
  const [accountOpen, setAccountOpen] = useState(false);
  return (
    <Layout className="app-shell app-shell--ambient" onKeyDown={event => {
      if (event.key === "Escape" && compactExpanded) { setCompactExpanded(false); event.preventDefault(); }
    }}>
      <Layout.Sider width={236} collapsedWidth={compact ? 0 : 80} collapsible collapsed={collapsed}
        onCollapse={value => compact ? setCompactExpanded(!value) : setDesktopCollapsed(value)}
        aria-hidden={compact && collapsed ? true : undefined} inert={compact && collapsed} trigger={null} theme="dark" className="app-sidebar">
        <div className="app-sidebar-brand">
          <img src="/system-logo.svg" width={32} height={32} alt="SyncSrv" />
          {!collapsed && <div className="app-sidebar-brand-copy"><span className="app-sidebar-brand-name">{data?.uiSettings?.systemName||"SyncSrv"}</span><span className="app-sidebar-brand-caption">备份 · 归档 · 恢复</span></div>}
        </div>
        <nav className="app-sidebar-navigation" aria-label="主导航">
          <Menu className="app-sidebar-menu" selectedKeys={[selectedKey]} mode="inline" theme="dark" inlineIndent={14}
            items={navigation} onClick={({key})=>{navigate(key); if (compact) setCompactExpanded(false);}} />
        </nav>
        <div className="app-sidebar-footer">
          <Tooltip title={collapsed ? "展开导航" : null} placement="right">
            <Button className="app-sidebar-collapse" type="text" block
              icon={<SidebarIcon name={collapsed ? "expand" : "collapse"} />}
              aria-label={collapsed ? "展开导航" : "收起导航"} aria-expanded={!collapsed}
              onClick={toggleNavigation}>{!collapsed && "收起导航"}</Button>
          </Tooltip>
        </div>
      </Layout.Sider>
      {compact && compactExpanded && <button type="button" className="app-sidebar-scrim" aria-label="关闭导航遮罩" tabIndex={-1} onClick={() => setCompactExpanded(false)} />}
      <Layout className="main-layout app-shell-workspace">
        <Layout.Header className="topbar app-shell-header">
          <div className="topbar-content">
            {compact && <Button type="text" className="app-mobile-navigation" aria-label={compactExpanded ? "关闭主导航" : "打开主导航"}
              aria-expanded={compactExpanded} icon={<SidebarIcon name={compactExpanded ? "collapse" : "expand"} />} onClick={toggleNavigation} />}
            <Typography.Text strong>{data?.uiSettings?.description||"备份、归档与恢复"}</Typography.Text>
            <Dropdown placement="bottomRight" trigger={["click"]}
              popupRender={menu=><div className="account-menu-popup">{menu}</div>}
              menu={{items:[
                {key:"status",disabled:true,label:<span className="account-service-state"><span className={`live-dot ${error ? "offline" : ""}`} />{error ? "连接异常" : data ? "服务在线" : "正在连接"}</span>},
                {key:"refresh",icon:<ReloadOutlined />,label:"刷新状态"},
                {key:"settings",icon:<UserOutlined />,label:"账号设置"},
                {key:"system",icon:<SettingOutlined />,label:canManageSettings?"系统设置":"系统设置（仅管理员）",disabled:!canManageSettings},
                {type:"divider"},
                {key:"version",disabled:true,label:`SyncSrv${data?.system.version ? ` / v${data.system.version}` : ""}`},
                {key:"logout",icon:<LogoutOutlined />,label:"退出登录",danger:true},
              ],onClick:({key})=>{
                if(key==="refresh")void refresh();
                else if(key==="settings")setAccountOpen(true);
                else if(key==="system")openSettings();
                else if(key==="logout")void action.run(async()=>{await api("/auth/logout","POST");onLogout();});
              }}}>
              <Button type="text" aria-label="账号菜单"><Space><Avatar size="small" icon={<UserOutlined />} /><Typography.Text>{data?.currentUser?.username || "admin"}</Typography.Text><DownOutlined /></Space></Button>
            </Dropdown>
          </div>
        </Layout.Header>
        <Layout.Content className="page-content app-main-content list-page app-main-content--glass">
          {error && (
            <Hint
              type="error"
              showIcon
              title={error}
              className="space-bottom"
            />
          )}
          {!data ? (
            <div className="loading-page">
              <Spin description="正在读取工作空间" />
            </div>
          ) : (
            <Suspense fallback={<Spin />}>
              <Routes location={settingsOpen ? settingsBackground : location}>
                <Route path="/dashboard" element={<Dashboard />} />
                <Route path="/jobs" element={<JobsPage />} />
                  <Route path="/agents" element={<AgentsPage />} />
                <Route path="/storages" element={<StoragePage />} />
                <Route path="/runs" element={<RunsPage />} />
                <Route path="/snapshots" element={<SnapshotsPage />} />
                <Route path="/audit" element={<AuditPage />} />
                <Route
                  path="*"
                  element={<Navigate to="/dashboard" replace />}
                />
              </Routes>
            </Suspense>
          )}

        </Layout.Content>
      </Layout>
      {settingsOpen && data && <Suspense fallback={<Spin />}><SettingsPage onClose={()=>navigate(settingsBackground,{replace:true})}/></Suspense>}
      {accountOpen && <AccountSettingsModal onClose={() => setAccountOpen(false)} onLogout={onLogout} />}
    </Layout>
  );
}
