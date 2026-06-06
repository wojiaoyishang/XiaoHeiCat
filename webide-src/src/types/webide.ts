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
