import { useEffect, useState } from 'react'
import type { HookSettings } from '../types/webide'

interface Props {
  settings: HookSettings | null
  onAppEnabledChange: (enabled: boolean) => void
  onScriptEnabledChange: (scriptId: string, enabled: boolean) => void
  onCachePrivateDirChange?: (enabled: boolean, targetScriptCacheDir?: string) => void
  onTargetCacheDirSave?: (targetScriptCacheDir: string) => void
  savingAppEnabled?: boolean
  savingScriptIds?: Set<string>
  savingCachePrivateDir?: boolean
  savingTargetCacheDir?: boolean
  disableFileLogging?: boolean
  savingDisableFileLogging?: boolean
  onDisableFileLoggingChange?: (enabled: boolean) => void
  onOpenScript: (path: string) => void
  onOpenSettings?: (scriptId: string, scriptPath: string) => void
}

export function HookSettingsPanel(props: Props) {
  const settings = props.settings
  const defaultDir = settings?.defaultTargetScriptCacheDir || '.xhh_scripts'
  const [cacheDirDraft, setCacheDirDraft] = useState(defaultDir)
  const [showHookOptions, setShowHookOptions] = useState(false)
  const cacheSaving = !!props.savingCachePrivateDir || !!props.savingTargetCacheDir

  useEffect(() => {
    setCacheDirDraft(settings?.targetScriptCacheDir || defaultDir)
  }, [settings?.packageName, settings?.targetScriptCacheDir, defaultDir])

  return (
    <aside className="panel right-panel hook-settings-panel">
      <div className="panel-title panel-title-row">
        <span>Hook 设置</span>
        <button
          className="small-icon-button"
          title="打开 Hook 设置选项"
          disabled={!settings}
          onClick={() => setShowHookOptions(true)}
        >⚙</button>
      </div>
      <div className="hook-settings-content">
        {!settings ? (
          <div className="small muted">请选择左侧应用。</div>
        ) : (
          <>
          <div className="small selected-app-box">
            <b>{settings.label || settings.packageName}</b>
            <br />
            <span className="muted">{settings.packageName}</span>
          </div>
          <div className="field-line setting-line-with-state">
            <label>
              <input
                type="checkbox"
                checked={!!settings.appEnabled}
                disabled={!!props.savingAppEnabled}
                onChange={(ev) => props.onAppEnabledChange(ev.target.checked)}
              />
              应用总开关 / Scope
            </label>
            {props.savingAppEnabled ? <span className="inline-loading"><span className="spinner" />保存中</span> : null}
          </div>
          {showHookOptions ? (
            <div className="ui-dialog-mask" onClick={() => setShowHookOptions(false)}>
              <div className="ui-dialog hook-options-dialog" onClick={(ev) => ev.stopPropagation()}>
                <div className="ui-dialog-header">
                  <div>
                    <h2>Hook 设置选项</h2>
                    <p className="muted">{settings.label || settings.packageName} · {settings.packageName}</p>
                  </div>
                  <button onClick={() => setShowHookOptions(false)}>×</button>
                </div>
                <div className="hook-options-body">
                  <div className="field-line hook-cache-config">
                    <label>
                      <input
                        type="checkbox"
                        checked={settings.cacheScriptsToPrivateDir !== false}
                        disabled={cacheSaving}
                        onChange={(ev) => props.onCachePrivateDirChange?.(ev.target.checked, cacheDirDraft)}
                      />
                      脚本缓存到私有目录
                    </label>
                    {props.savingCachePrivateDir ? <span className="inline-loading"><span className="spinner" />保存缓存开关中</span> : null}
                    <div className="small muted">开启后目标进程会把脚本缓存到目标应用自己的 filesDir 下。每个应用独立保存到 LSPosed Remote Preferences。</div>
                    <div className="cache-dir-row">
                      <input
                        type="text"
                        value={cacheDirDraft}
                        placeholder={defaultDir}
                        disabled={cacheSaving}
                        onChange={(ev) => setCacheDirDraft(ev.target.value)}
                        title="目标应用 filesDir 下的相对目录。默认 .xhh_scripts 为隐藏目录；自定义目录会保存为普通可见目录，例如 xhh_scripts 或 cache/xhh_scripts"
                      />
                      <button disabled={cacheSaving} onClick={() => props.onTargetCacheDirSave?.(cacheDirDraft)}>
                        {props.savingTargetCacheDir ? <><span className="spinner" />保存中...</> : '保存路径'}
                      </button>
                    </div>
                    <div className="small muted">默认 {defaultDir} 保持隐藏目录；自定义目录会规范化为普通可见目录，例如 xhh_scripts 或 cache/xhh_scripts。</div>
                  </div>
                  <div className="field-line hook-cache-config">
                    <label>
                      <input
                        type="checkbox"
                        checked={!!props.disableFileLogging}
                        disabled={!!props.savingDisableFileLogging}
                        onChange={(ev) => props.onDisableFileLoggingChange?.(ev.target.checked)}
                      />
                      不记录日志
                    </label>
                    {props.savingDisableFileLogging ? <span className="inline-loading"><span className="spinner" />保存日志设置中</span> : null}
                    <div className="small muted">开启后只通过实时广播同步日志到 WebIDE，不再持久化写入日志文件。默认关闭。</div>
                  </div>
                </div>
                <div className="ui-dialog-actions">
                  <button className="primary" onClick={() => setShowHookOptions(false)}>完成</button>
                </div>
              </div>
            </div>
          ) : null}
          <div className="sub-title">匹配脚本</div>
          <div className="hook-list">
            {!settings.scripts?.length ? (
              <div className="small muted">没有匹配当前应用的脚本。请检查 @target。</div>
            ) : (
              settings.scripts.map((script) => (
                <div className="hook-item clickable script-hover-open" key={script.id || script.path} title="在编辑器中打开" onClick={() => props.onOpenScript(script.path)}>
                  <div className="hook-item-head">
                    {script.hasSettings ? (
                      <button
                        className="small-icon-button"
                        title="打开脚本设置"
                        onClick={(ev) => {
                          ev.stopPropagation();
                          props.onOpenSettings?.(script.id, script.path);
                        }}
                      >⚙</button>
                    ) : null}
                    <label onClick={(ev) => ev.stopPropagation()}>
                      <input
                        type="checkbox"
                        checked={!!script.enabled}
                        disabled={props.savingScriptIds?.has(script.id)}
                        onChange={(ev) => props.onScriptEnabledChange(script.id, ev.target.checked)}
                      />
                      <span className="title"> {script.name || script.id}</span>
                      {props.savingScriptIds?.has(script.id) ? <span className="inline-loading"><span className="spinner" />保存中</span> : null}
                    </label>
                  </div>
                  <div className="desc">{script.path}<br />{script.id}</div>
                </div>
              ))
            )}
          </div>
          </>
        )}
      </div>
    </aside>
  )
}
