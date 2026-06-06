import type { AppInfo } from '../types/webide'

interface Props {
  apps: AppInfo[]
  selectedPackage?: string
  query: string
  showSystem: boolean
  onQueryChange: (query: string) => void
  onShowSystemChange: (show: boolean) => void
  onReload: () => void
  onSelect: (app: AppInfo) => void
}

export function AppListPanel(props: Props) {
  return (
    <aside className="panel app-panel">
      <div className="panel-title">软件列表</div>
      <div className="row compact">
        <input
          type="text"
          value={props.query}
          placeholder="搜索应用名或包名"
          onChange={(ev) => props.onQueryChange(ev.target.value)}
        />
        <button onClick={props.onReload}>刷新</button>
      </div>
      <label className="inline check-line">
        <input
          type="checkbox"
          checked={props.showSystem}
          onChange={(ev) => props.onShowSystemChange(ev.target.checked)}
        />
        显示系统应用
      </label>
      <div className="list app-list">
        {props.apps.map((app) => (
          <div
            key={app.packageName}
            className={'item' + (props.selectedPackage === app.packageName ? ' selected' : '')}
            onClick={() => props.onSelect(app)}
            title={app.packageName}
          >
            <div className="name">
              {app.label || app.packageName}
              {app.enabled && <span className="badge enabled">启用</span>}
              {app.system && <span className="badge">系统</span>}
            </div>
            <div className="pkg">{app.packageName}</div>
          </div>
        ))}
      </div>
    </aside>
  )
}
