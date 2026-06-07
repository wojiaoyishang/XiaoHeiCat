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

export type SettingType =
  | 'list'
  | 'group'
  | 'heading'
  | 'info'
  | 'switch'
  | 'number'
  | 'text'
  | 'checkbox'
  | 'radio'
  | 'select'
  | 'custom'
  | 'tags'

export interface SettingOption {
  label: string
  value: unknown
}

export interface SettingField {
  type: SettingType | string
  key?: string
  label?: string
  title?: string
  description?: string
  message?: string
  placeholder?: string
  tone?: 'info' | 'warning' | 'success' | 'error' | string
  default?: unknown
  value?: unknown
  options?: SettingOption[]
  items?: SettingField[]
  min?: number
  max?: number
  step?: number
  integer?: boolean
  masked?: boolean
  multiline?: boolean
  maxLength?: number
  maxItems?: number
  uniqueKey?: string
  keyPlaceholder?: string
  valuePlaceholder?: string
  collapsible?: boolean
  defaultCollapsed?: boolean
}

export interface ScriptSettingsSchema {
  version: number
  title?: string
  description?: string
  fields: SettingField[]
}

export interface ScriptSettingsResponse {
  ok: boolean
  packageName: string
  scriptId: string
  scriptPath: string
  storageKey: string
  schema: ScriptSettingsSchema
  values: Record<string, unknown>
  mergedValues: Record<string, unknown>
}

export interface ScriptSettingsSaveResponse {
  ok: boolean
  packageName: string
  scriptId: string
  scriptPath: string
  storageKey: string
  values?: Record<string, unknown>
  mergedValues: Record<string, unknown>
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
  kind?: 'file' | 'directory' | string
  entryPath?: string
  rootPath?: string
  url?: string
  urlRefreshOnApply?: boolean
  hasSettings?: boolean
  settingsPath?: string
  settingsSchema?: ScriptSettingsSchema | null
}

export interface ScriptInfo {
  path: string
  name?: string
  id?: string
  enabled?: boolean
  metadata?: ScriptMeta
  kind?: 'file' | 'directory' | string
  entryPath?: string
  rootPath?: string
  hasSettings?: boolean
  settingsPath?: string
}

export interface HookScriptInfo {
  id: string
  name?: string
  path: string
  enabled: boolean
  hasSettings?: boolean
  settingsPath?: string
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

export interface ScriptTreeNode {
  type: 'file' | 'directory'
  name: string
  path: string
  script?: boolean
  entry?: boolean
  metadata?: ScriptMeta
  children?: ScriptTreeNode[]
}

export interface ScriptTreeResponse {
  ok: boolean
  root: ScriptTreeNode
}

export interface ScriptFileEntry {
  type: 'file' | 'directory'
  name: string
  path: string
  extension?: string
  size?: number
  modifiedAt?: number
  scriptRole?: 'script' | 'entry' | 'dependency' | ''
}

export interface ScriptFileListResponse {
  ok: boolean
  dir: string
  parent: string
  entries: ScriptFileEntry[]
}
