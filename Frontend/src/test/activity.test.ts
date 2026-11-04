import { describe, expect, it } from "vitest";
import { mergeActivity } from "../modules/jobs/activity";
import { newerRelease } from "../modules/agents/AgentUpdates";
import type { Run } from "../shared/types";
import type { SourceChange } from "../shared/types";
const change=(id:string,runId="",status="WAITING")=>({id,jobId:"job",runId,status,phase:"DEBOUNCING",receivedAt:"2026-09-18T01:00:00Z"} as SourceChange);
const run={id:"run1",jobId:"job",status:"RUNNING",phase:"ARCHIVING",startedAt:"2026-09-18T02:00:00Z"} as Run;
describe("unified execution activity",()=>{
  it("groups pending files before an execution exists",()=>{
    const rows=mergeActivity([], [change("one"),change("two")]);
    expect(rows).toHaveLength(1);expect(rows[0].status).toBe("QUEUED");expect(rows[0].changes).toHaveLength(2);
  });
  it("shows one execution row after changes are claimed",()=>{
    const rows=mergeActivity([run],[change("one","run1","RUNNING"),change("two","run1","COMPLETED")]);
    expect(rows).toHaveLength(1);expect(rows[0].status).toBe("RUNNING");expect(rows[0].changes).toHaveLength(2);
  });
  it("keeps a newer pending generation separate from an older completed run",()=>{
    const rows=mergeActivity([{...run,status:"COMPLETED"}],[change("one")]);
    expect(rows).toHaveLength(2);expect(rows.map(r=>r.status)).toContain("QUEUED");
  });
  it("does not hide changes whose execution is outside the fetched history",()=>{
    expect(mergeActivity([], [change("one","older","FAILED")])[0].status).toBe("FAILED");
  });
});
it("compares versions numerically and never prompts a downgrade",()=>{
  expect(newerRelease("0.10.0-dev","0.9.0-dev")).toBe(true);
  expect(newerRelease("0.7.0","0.7.0-dev")).toBe(true);
  expect(newerRelease("0.7.0-dev","0.7.0")).toBe(false);
  expect(newerRelease("0.7.0-dev","0.7.0-dev")).toBe(false);
  expect(newerRelease("invalid","0.6.0-dev")).toBe(false);
});
