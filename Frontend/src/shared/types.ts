export type Endpoint = {
  agentId?: string;
  agentRoot?: string;
  allowRelay?: boolean;
  id: string;
  name: string;
  protocol: string;
  host: string;
  root: string;
  username: string;
  password: string;
  domain: string;
  hostKey: string;
  port: number;
  createdAt: string;
};
export type Job = {
  id: string;
  name: string;
  sourceId: string;
  targetId: string;
  archiveId: string;
  intervalSeconds: number;
  discoveryMode?: "SCAN" | "EVENTS";
  sourceMode?: "CONNECTION" | "AGENT";
  agentId?: string;
  agentRoot?: string;
  reconcileSeconds?: number;
  bandwidthMiB: number;
  transferThreads?: number;
  archiveThreads?: number;
  adaptiveTransfers?: boolean;
  maxTransferThreads?: number;
  enabled: boolean;
  createdAt: string;
};
export type RunProgressSummary = {
  planComplete:boolean; processedBytes:number; totalBytes:number; processedFiles:number; totalFiles:number;
  activeFiles?:number; peakActiveFiles?:number; concurrencyLimit?:number;
  activeChecks?:number; peakChecks?:number;
  entries:number; elapsedMillis:number; estimatedTotalMillis?:number|null;
  transferBytesPerSecond:number; verificationBytesPerSecond:number; sampledAt:string;
};
export type RunFile = {id:number;path:string;kind:string;status:string;size:number;processedBytes:number;
  phase:string;phaseBytes:number;phaseTotal:number;error:string};
export type RunFiles = {run:Run;coverage:string;entries:RunFile[];total:number;page:number;pageSize:number};
export type Run = {
  sourceTransfer?: {initializationMillis:number;transferMillis:number;finalizationMillis:number;concurrency:number;maxConcurrency:number;automatic:boolean;reason:string};
  executionMode?: string;
  executionAgentId?: string;
  executionNote?: string;
  progress?:RunProgressSummary;
  id: string;
  jobId: string;
  kind: string;
  snapshotId: string;
  target: string;
  restoreEndpointId?: string;
  actor: string;
  status: string;
  phase: string;
  currentPath: string;
  startedAt: string;
  queuedAt?: string;
  actualStartedAt?: string;
  discoveryMode?: string;
  trigger?: string;
  completedAt: string;
  error: string;
  bytes: number;
  verifiedBytes?: number;
  phaseBytes?: number;
  phaseTotal?: number;
  done: number;
  total: number;
};
export type Snapshot = {
  id: string;
  jobId: string;
  sequence: number;
  startedAt: string;
  completedAt: string;
  entries: number;
  files: number;
  totalBytes: number;
  manifestHash: string;
};
export type AuditHead = {
  sequence: number;
  head: string;
  publicKey: string;
  protection: string;
};
export type AuditEvent = {
  sequence: number;
  time: string;
  actor: string;
  action: string;
  subject: string;
  detail: string;
  previous: string;
  hash: string;
  signature: string;
};
export type AppState = {
  uiSettings?: import("../modules/settings/settingsModel").UiSettings;
 totals?:Record<string,number>;
 currentUser?:{username:string;role:string};
  endpoints: Endpoint[];
  jobs: Job[];
  runs: Run[];
  snapshots: Snapshot[];
  settings: { bandwidthMiB: number };
  audit: AuditHead;
  system: {
    version: string;
    java: string;
    database: string;
    port: number;
    bindAddress?: string;
    maxConcurrent: number;
  };
};
export type RestoreItem = {
  path: string;
  versionId: string;
  endpointId: string;
  location: string;
  hash: string;
  size: number;
  decision: string;
};
export type RestorePlan = {
  snapshot: { id: string; manifestHash: string; completedAt: string };
  items: RestoreItem[];
  excludedCurrentPaths: string[];
};

export type Agent = {
  id: string;
  name: string;
  platform: string;
  arch: string;
  version: string;
  publicKey: string;
  status: string;
  createdAt: string;
  lastSeen: string;
  capability: string;
  error: string;
  configVersion: number;
  appliedVersion: number;
  pendingEvents: number;
  backlog?: AgentBacklogSummary;
  databaseBytes: number;
  memoryBytes: number;
  uploadedBytes?: number;
  uploadedFiles?: number;
  cpuPercent: number | null;
  watchingJobs: string[];
};
export type Discovery = {
  jobId: string;
  mode: string;
  pendingCount: number;
  oldestPendingAt: string;
  checkpoint: {
    lastFullScan: string;
    snapshotId: string;
    entries: number;
  } | null;
  pending: {
    id: string;
    path: string;
    reason: string;
    firstSeen: string;
    updatedAt: string;
    merged: number;
    sourceCoalesced?: number;
    error: string;
  }[];
};

export type SourceChange = {
  id: string; jobId: string; path: string; status: string; phase: string;
  observedAt: string; receivedAt: string; updatedAt: string; startedAt: string;
  completedAt: string; runId: string; error: string; currentPath: string; coalesced: number;
};

export type AgentBacklogSummary = {
  knownPaths: number; waiting: number; running: number; overdue: number; oldestWaitingAt: string;
};
export type AgentBacklogRow = {
  change: SourceChange; jobName: string; enabled: boolean; eligibleAt: string; overdue: boolean;
};
export type AgentBacklogView = {
  entries: AgentBacklogRow[]; total: number; page: number; pageSize: number; summary: AgentBacklogSummary;
};
