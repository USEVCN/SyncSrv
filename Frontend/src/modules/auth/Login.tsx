import { LockOutlined, UserOutlined } from "@ant-design/icons";
import { Button, Card, ConfigProvider, Form, Input, theme } from "antd";
import zhCN from "antd/locale/zh_CN";
import enUS from "antd/locale/en_US";
import { useRef, useState } from "react";
import { api } from "../../platform/http";
import { version as clientVersion } from "../../../package.json";
import { AuthErrorAlert } from "./AuthErrorAlert";
import { AuthLanguageSwitch } from "./AuthLanguageSwitch";
import { useLoginLocale } from "./loginLocale";
import LoginAurora from "./LoginAurora";
import LoginBorderGlow from "./LoginBorderGlow";
import LoginParticleBrand from "./LoginParticleBrand";
import "./LoginPage.css";

export function Login({ onLogin }: { onLogin: () => void }) {
  const { token } = theme.useToken();
  const { locale, setLocale, text, form } = useLoginLocale();
  const [error, setError] = useState<Error | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const pending = useRef(false);

  async function submit(values: { username: string; password: string }) {
    if (pending.current) return;
    pending.current = true;
    setSubmitting(true);
    setError(null);
    try {
      await api("/auth/login", "POST", values);
      onLogin();
    } catch (error) {
      setError(error instanceof Error ? error : new Error(String(error)));
    } finally {
      pending.current = false;
      setSubmitting(false);
    }
  }

  return <ConfigProvider locale={locale === "en" ? enUS : zhCN}>
    <main className="auth-login auth-card-scope" lang={locale}>
      <LoginAurora />
      <AuthLanguageSwitch locale={locale} onChange={setLocale} />
      <LoginBorderGlow>
        <Card className="auth-login-card">
          <LoginParticleBrand />
          <Form form={form} className="auth-login-form" layout="vertical" size="large"
            onFinish={submit} requiredMark={false}>
            <Form.Item name="username" className="auth-login-account"
              rules={[{ required: true, message: text.accountRequired }]}>
              <Input prefix={<UserOutlined style={{ color: token.colorTextTertiary }} />}
                aria-label={text.account} autoComplete="username" placeholder={text.account} />
            </Form.Item>
            <Form.Item name="password" className="auth-login-password"
              rules={[{ required: true, message: text.passwordRequired }]}>
              <Input.Password prefix={<LockOutlined style={{ color: token.colorTextTertiary }} />}
                aria-label={text.password} autoComplete="current-password" placeholder={text.password} />
            </Form.Item>
            <AuthErrorAlert error={error} locale={locale} />
            <Button type="primary" htmlType="submit" block loading={submitting}
              className="auth-login-submit">{text.signIn}</Button>
          </Form>
        </Card>
        <span className="auth-login-floor" aria-hidden="true" />
      </LoginBorderGlow>
      <footer className="auth-login-footer">SyncSrv <span aria-hidden="true">·</span> v{clientVersion}</footer>
    </main>
  </ConfigProvider>;
}
