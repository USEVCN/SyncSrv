import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import type { Agent } from "../shared/types";
import { AgentConfiguration, agentHealth } from "../modules/agents/AgentHealth";
const device={status:"APPROVED",lastSeen:"2026-09-19T00:00:00Z",configVersion:3,appliedVersion:3} as Agent;
it("legacy file occupation does not invalidate the applied configuration",()=>{
 const error="Notification delivery: openat file.iso: The process cannot access the file because it is being used by another process.";
 render(<AgentConfiguration agent={{...device,error}} />);
 expect(screen.getByText("已生效")).toBeInTheDocument();
 expect(screen.getByRole("button",{name:"文件占用，等待重试"})).toBeInTheDocument();
 expect(screen.queryByText("应用异常")).not.toBeInTheDocument();
});
it("configuration and source issues can coexist without hiding either",()=>{
 const error="invalid root Source delivery: SOURCE_BUSY: locked.iso";
 expect(agentHealth(error).configuration).toBe("invalid root");
 render(<AgentConfiguration agent={{...device,error}} />);
 expect(screen.getByText("配置异常")).toBeInTheDocument();
 expect(screen.getByRole("button",{name:"配置或监听异常"})).toBeInTheDocument();
});
it("permissions and network failures are not called file locks",()=>{
 expect(agentHealth("Source delivery: access denied").busy).toBe(false);
 render(<AgentConfiguration agent={{...device,error:"Source delivery: connection refused"}} />);
 expect(screen.getByRole("button",{name:"备份待重试"})).toBeInTheDocument();
});
it("successful retry removes the warning",()=>{
 const {rerender}=render(<AgentConfiguration agent={{...device,error:"Source delivery: SOURCE_BUSY: locked.iso"}} />);
 rerender(<AgentConfiguration agent={{...device,error:""}} />);
 expect(screen.queryByRole("button",{name:"文件占用，等待重试"})).not.toBeInTheDocument();
 expect(screen.getByText("已生效")).toBeInTheDocument();
});
