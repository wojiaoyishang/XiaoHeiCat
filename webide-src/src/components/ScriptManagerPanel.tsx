import { useMemo, useState } from 'react'
import type { ScriptInfo } from '../types/webide'

interface Props {
  scripts: ScriptInfo[]
  currentPath?: string | null
  onOpenScript: (path: string) => void
  onNewScript: () => void
  onReload: () => void
}

export function ScriptManagerPanel({ scripts, currentPath, onOpenScript, onNewScript, onReload }: Props) {
  const [query, setQuery] = useState('')
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return scripts
    return scripts.filter((script) => {
      const haystack = [
        script.path,
        script.id || '',
        script.name || '',
        script.metadata?.description || '',
        ...(script.metadata?.targets || [])
      ].join('\n').toLowerCase()
      return haystack.includes(q)
    })
  }, [scripts, query])

  return (
    <aside className="panel left-panel script-manager-panel">
      <div className="panel-title">全部脚本</div>
      <div className="row compact">
        <input
          type="text"
          value={query}
          placeholder="搜索脚本名 / ID / 目标包名"
          onChange={(ev) => setQuery(ev.target.value)}
        />
        <button onClick={onReload} title="刷新全部脚本">刷新</button>
      </div>
      <div className="row compact script-manager-actions">
        <button onClick={onNewScript}>新建脚本</button>
        <span className="muted">{filtered.length} / {scripts.length}</span>
      </div>
      <div className="list script-list-all">
        {filtered.map((script) => (
          <div
            key={script.path}
            className={'script-row' + (currentPath === script.path ? ' selected' : '')}
            title={`打开脚本：${script.path}`}
            onClick={() => onOpenScript(script.path)}
          >
            <div className="script-row-name">{script.name || script.id || script.path}</div>
            <div className="script-row-path">{script.path}</div>
            <div className="script-row-meta">
              {(script.metadata?.targets || []).slice(0, 3).join(', ') || '未声明 @target'}
            </div>
          </div>
        ))}
        {!filtered.length ? <div className="small muted">没有脚本。</div> : null}
      </div>
    </aside>
  )
}
