import { Alert } from "antd";
import { ApiError } from "../../platform/http";
import { loginMessages, type LoginLocale } from "./loginLocale";

const translations: Record<string, string> = {
  "用户名或密码不正确": "Incorrect username or password",
  "登录失败次数过多，请 5 分钟后再试": "Too many sign-in attempts. Please try again in 5 minutes.",
};

export function AuthErrorAlert({ error, locale }: { error: Error | null; locale: LoginLocale }) {
  if (!error) return null;
  const text = loginMessages[locale];
  const original = error.message;
  const translated = translations[original];
  const untranslated = locale === "en" && !translated && /[\u3400-\u9fff]/.test(original);
  const summary = locale === "en" ? translated || (untranslated ? text.failed : original) : original;
  return <Alert type="error" showIcon title={summary || text.failed} className="auth-login-error"
    description={<>
      {error instanceof ApiError && <details style={{ marginTop: 8 }}>
        <summary style={{ cursor: "pointer" }}>{text.diagnostics}</summary>
        <dl style={{ margin: "8px 0 0" }}><div style={{ marginTop: 4 }}>
          <dt style={{ display: "inline", fontWeight: 600 }}>{text.status}{locale === "en" ? ": " : "："}</dt>
          <dd style={{ display: "inline", margin: 0 }}>{error.status}</dd>
        </div></dl>
      </details>}
      {untranslated && <details style={{ marginTop: 8 }}>
        <summary>{text.originalMessage}</summary><div>{original}</div>
      </details>}
    </>} />;
}
