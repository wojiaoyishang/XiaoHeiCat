import type { ScriptMeta } from '../types/webide'

interface Props {
  path?: string | null
  metadata: ScriptMeta | null
  dirty: boolean
}

export function ScriptInfoPanel({ path, metadata, dirty }: Props) {
  return (
    <aside className="panel right-panel">
      <div className="panel-title">脚本信息</div>
      <div className="small selected-app-box">
        <b>{path || '未打开脚本'}</b>
        {dirty ? <span className="dirty-mark"> 未保存</span> : null}
      </div>
      <div className="sub-title">Metadata</div>
      <pre className="metadata">
        {metadata ? JSON.stringify({
          id: metadata.id,
          name: metadata.name,
          version: metadata.version,
          author: metadata.author,
          description: metadata.description,
          target: metadata.targets || [],
          process: metadata.processes || [],
          runAt: metadata.runAt,
          grant: metadata.grants || [],
          url: metadata.url || '',
          urlRefreshOnApply: !!metadata.urlRefreshOnApply
        }, null, 2) : '未打开脚本'}
      </pre>
    </aside>
  )
}
