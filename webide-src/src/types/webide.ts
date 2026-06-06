export interface ApiStatus {
  ok: boolean
  server?: string
  process?: string
  pid?: number
  host?: string
  port?: number
  scriptDir?: string
  uptimeMs?: number
  running?: boolean
  baseUrl?: string
  hasAllFilesAccess?: boolean
  xposedService?: boolean
  remotePreferences?: boolean
  mainProcess?: string
  bridgeOk?: boolean
}

export interface AppInfo {
  label: string
  packageName: string
  system: boolean
  enabled: boolean
  scopeEnabled?: boolean
  scriptCount?: number
}

export interface ScriptMeta {
  id?: string
  name?: string
  version?: string
  author?: string
  description?: string
  targets?: string[]
  processes?: string[]
  runAt?: string
  grants?: string[]
  url?: string
  urlRefreshOnApply?: boolean
}

export interface ScriptInfo {
  path: string
  name?: string
  id?: string
  enabled?: boolean
  metadata?: ScriptMeta
}

export interface HookScriptInfo {
  id: string
  name?: string
  path: string
  enabled: boolean
}

export interface HookSettings {
  ok: boolean
  packageName: string
  label: string
  appEnabled: boolean
  scopeEnabled?: boolean
  scripts: HookScriptInfo[]
}

export interface ReadScriptResponse {
  ok: boolean
  path: string
  content: string
  metadata?: ScriptMeta
}

export interface DebugEvent {
  type: 'paused' | 'continuing' | 'continued' | 'resumed' | 'aborting' | 'aborted' | 'expired' | 'variablesUpdated' | 'evalResult' | 'log' | string
  pauseId?: string
  packageName?: string
  processName?: string
  scriptName?: string
  scriptPath?: string
  sourceName?: string
  breakpointName?: string
  threadName?: string
  breakpointType?: string
  line?: number
  time?: number
  pid?: number
  locals?: unknown
  message?: string
  editableLocals?: boolean
  hasReturnValue?: boolean
  returnValue?: unknown
  expression?: string
  result?: unknown
  ok?: boolean
  text?: string | null
  error?: string
  status?: string
  active?: boolean
  updatedAt?: number
}

export interface DebugBreakpointsResponse {
  ok: boolean
  packageName: string
  scriptPath?: string | null
  lines: number[]
  breakpoints?: Record<string, number[]>
}
