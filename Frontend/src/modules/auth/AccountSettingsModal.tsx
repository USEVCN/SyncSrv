import { ResizableModal as Modal } from "../../shared/ResizableModal";
import { Button, Form, Input, Space, Tabs } from "antd";
import { api } from "../../platform/http";
import { useAction } from "../../shared/ui";

import {useEffect,useState} from "react";
import {AccountManagement} from "./AccountManagement";

export function AccountSettingsModal({ onClose, onLogout }: {
  onClose: () => void;
  onLogout: () => void;
}) {
  const action = useAction();
  const [me,setMe]=useState({username:"",role:""});
  useEffect(()=>{void api<typeof me>("/auth/me").then(v=>setMe({username:v.username||"",role:v.role||""})).catch(()=>{});},[]);
  const [tab,setTab]=useState("password");
  return (
    <Modal title="账号设置" open onCancel={onClose} footer={null} width={480}
      closable={!action.loading} maskClosable={!action.loading} keyboard={!action.loading}>
      <Tabs activeKey={tab} onChange={setTab} items={[{key:"password",label:"修改密码"},...(me.role==="ADMIN"?[{key:"accounts",label:"账号管理"}]:[])]}/>
      {tab==="accounts"&&me.role==="ADMIN"?<AccountManagement/>:<Form layout="vertical" name="account-password" onFinish={(values) =>
        action.run(async () => {
          await api("/auth/password", "POST", { current: values.current, next: values.next });
          onClose();
          onLogout();
        }, "密码已更新，请重新登录")
      }>
        <Form.Item label="账号"><Input value={me.username} readOnly /></Form.Item>
        <Form.Item name="current" label="当前密码" rules={[{ required: true, message: "请输入当前密码" }]}>
          <Input.Password autoComplete="current-password" />
        </Form.Item>
        <Form.Item name="next" label="新密码" rules={[{ required: true, min: 10, message: "新密码至少 10 个字符" }]}>
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Form.Item name="confirm" label="确认新密码" dependencies={["next"]} rules={[
          { required: true, message: "请再次输入新密码" },
          ({ getFieldValue }) => ({ validator: (_, value) => value === getFieldValue("next")
            ? Promise.resolve() : Promise.reject(new Error("两次密码不一致")) }),
        ]}>
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Space className="full-width" style={{ justifyContent: "flex-end" }}>
          <Button onClick={onClose} disabled={action.loading}>取消</Button>
          <Button type="primary" htmlType="submit" loading={action.loading}>更新密码</Button>
        </Space>
      </Form>}
    </Modal>
  );
}
