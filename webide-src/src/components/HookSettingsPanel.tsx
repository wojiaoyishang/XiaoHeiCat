import type { HookSettings, ScriptMeta } from '../types/webide'

interface Props {
  settings: HookSettings | null
  metadata: ScriptMeta | null
  onAppEnabledChange: (enabled: boolean) => void
  onScriptEnabledChange: (scriptId: string, enabled: boolean) => void
  onOpenScript: (path: string) => void
}

export function HookSettingsPanel(props: Props) {
  const settings = props.settings
  return (
    <aside className="panel right-panel">
      <div className="panel-title">Hook 设置</div>
      {!settings ? (
        <div className="small muted">请选择左侧应用。</div>
      ) : (
        <>
          <div className="small selected-app-box">
            <b>{settings.label || settings.packageName}</b>
            <br />
            <span className="muted">{settings.packageName}</span>
          </div>
          <div className="field-line">
            <label>
              <input
                type="checkbox"
                checked={!!settings.appEnabled}
                onChange={(ev) => props.onAppEnabledChange(ev.target.checked)}
              />
              应用总开关 / Scope
            </label>
          </div>
          <div className="sub-title">匹配脚本</div>
          <div className="hook-list">
            {!settings.scripts?.length ? (
              <div className="small muted">没有匹配当前应用的脚本。请检查 @target。</div>
            ) : (
              settings.scripts.map((script) => (
                <div className="hook-item clickable script-hover-open" key={script.id || script.path} title="在编辑器中打开" onClick={() => props.onOpenScript(script.path)}>
                  <label onClick={(ev) => ev.stopPropagation()}>
                    <input
                      type="checkbox"
                      checked={!!script.enabled}
                      onChange={(ev) => props.onScriptEnabledChange(script.id, ev.target.checked)}
                    />
                    <span className="title"> {script.name || script.id}</span>
                  </label>
                  <div className="desc">{script.path}<br />{script.id}</div>
                </div>
              ))
            )}
          </div>
          <div className="sub-title">当前脚本 Metadata</div>
          <pre className="metadata">
            {props.metadata ? JSON.stringify({
              id: props.metadata.id,
              name: props.metadata.name,
              version: props.metadata.version,
              author: props.metadata.author,
              description: props.metadata.description,
              target: props.metadata.targets || [],
              process: props.metadata.processes || [],
              runAt: props.metadata.runAt,
              grant: props.metadata.grants || [],
              url: props.metadata.url || '',
              urlRefreshOnApply: !!props.metadata.urlRefreshOnApply
            }, null, 2) : '未打开脚本'}
          </pre>
        </>
      )}
    </aside>
  )
}
