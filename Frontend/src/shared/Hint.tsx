import { Button, Popover } from "antd";
import { CheckCircleOutlined, ExclamationCircleOutlined, InfoCircleOutlined } from "@ant-design/icons";
import { useState, type CSSProperties, type ReactNode } from "react";
import { storageText } from "./storageText";

type Props = {
  title: ReactNode; description?: ReactNode; label?: string; iconOnly?: boolean;
  type?: "info" | "warning" | "error" | "success";
  className?: string; style?: CSSProperties; showIcon?: boolean;
};
/** Shared help surface: hover for mouse users, focus/click for keyboard and touch. */
export function Hint({ title, description, label, iconOnly, type = "info", className = "", style }: Props) {
  const [open, setOpen] = useState(false);
  if (typeof title === "string") title = storageText(title);
  if (typeof description === "string") description = storageText(description);
  if (label) label = storageText(label);
  const text = label || (typeof title === "string" && title.length <= 32 ? title :
    { info: "查看说明", warning: "查看提醒", error: "查看错误", success: "查看结果" }[type]);
  const icon = type === "success" ? <CheckCircleOutlined /> :
    ["warning", "error"].includes(type) ? <ExclamationCircleOutlined /> : <InfoCircleOutlined />;
  return <span className={`hover-hint hint-${type} ${className}`} style={style}>
    <Popover open={open} onOpenChange={setOpen} trigger={["hover", "focus", "click"]}
      classNames={{ root: "hint-popover" }}
      placement="topLeft" mouseEnterDelay={0.15} mouseLeaveDelay={0.2}
      title={description ? title : undefined}
      content={<div className="hint-content">{description || title}</div>}
      styles={{ root: { maxWidth: "min(560px, calc(100vw - 32px))" } }}>
      <Button type="text" size="small" icon={icon} aria-label={text} aria-expanded={open}
        onKeyDown={e => { if (e.key === "Escape") { setOpen(false); e.stopPropagation(); } }}>
        {iconOnly ? null : text}
      </Button>
    </Popover>
  </span>;
}
