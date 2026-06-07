import { useEffect, useMemo, useState } from 'react'
import type { ScriptFileEntry, ScriptInfo } from '../types/webide'

interface Props {
  scripts: ScriptInfo[]
  entries: ScriptFileEntry[]
  currentDir: string
  currentPath?: string | null
  onOpenScript: (path: string) => void
  onOpenDir: (path: string) => void
  onGoUp: () => void
  onNewSingleScript: () => void
  onNewMultiScript: () => void
  onNewFile: (baseDir?: string) => void
  onNewFolder: (baseDir?: string) => void
  onRenamePath: (path: string) => void
  onDeletePath: (path: string) => void
  onReload: () => void
}

function scriptRoleLabel(entry: ScriptFileEntry) {
  if (entry.scriptRole === 'entry') return '入口'
  if (entry.scriptRole === 'script') return '脚本'
  if (entry.scriptRole === 'dependency') return '依赖'
  return ''
}

function formatSize(size?: number) {
  if (!Number.isFinite(size || 0) || !size) return ''
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

function formatTime(ms?: number) {
  if (!Number.isFinite(ms || 0) || !ms) return ''
  try {
    return new Date(ms).toLocaleString()
  } catch {
    return ''
  }
}

function crumbParts(dir: string) {
  const clean = dir.replace(/^\/+|\/+$/g, '')
  if (!clean) return []
  return clean.split('/').filter(Boolean)
}

interface ContextMenuState {
  x: number
  y: number
  entry: ScriptFileEntry
}

export function ScriptManagerPanel({
  scripts,
  entries,
  currentDir,
  currentPath,
  onOpenScript,
  onOpenDir,
  onGoUp,
  onNewSingleScript,
  onNewMultiScript,
  onNewFile,
  onNewFolder,
  onRenamePath,
  onDeletePath,
  onReload,
}: Props) {
  const [query, setQuery] = useState('')
  const [menu, setMenu] = useState<ContextMenuState | null>(null)
  const scriptByPath = useMemo(() => {
    const map = new Map<string, ScriptInfo>()
    scripts.forEach((script) => map.set(script.path, script))
    return map
  }, [scripts])

  useEffect(() => {
    if (!menu) return
    const close = () => setMenu(null)
    const onKey = (ev: KeyboardEvent) => {
      if (ev.key === 'Escape') setMenu(null)
    }
    window.addEventListener('click', close)
    window.addEventListener('blur', close)
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('click', close)
      window.removeEventListener('blur', close)
      window.removeEventListener('keydown', onKey)
    }
  }, [menu])

  const filteredEntries = useMemo(() => {
    const q = query.trim().toLowerCase()
    const sorted = [...entries].sort((a, b) => {
      if (a.type !== b.type) return a.type === 'directory' ? -1 : 1
      return a.name.localeCompare(b.name)
    })
    if (!q) return sorted
    return sorted.filter((entry) => {
      const script = scriptByPath.get(entry.path)
      const haystack = [
        entry.name,
        entry.path,
        entry.type,
        entry.scriptRole || '',
        script?.id || '',
        script?.name || '',
        script?.metadata?.description || '',
        ...(script?.metadata?.targets || []),
      ].join('\n').toLowerCase()
      return haystack.includes(q)
    })
  }, [entries, query, scriptByPath])

  const crumbs = crumbParts(currentDir)
  const canGoUp = currentDir.trim() !== ''

  const openEntry = (entry: ScriptFileEntry) => {
    if (entry.type === 'directory') onOpenDir(entry.path)
    else onOpenScript(entry.path)
  }

  const showEntryMenu = (ev: React.MouseEvent, entry: ScriptFileEntry) => {
    ev.preventDefault()
    ev.stopPropagation()
    setMenu({ x: ev.clientX, y: ev.clientY, entry })
  }

  return (
    <aside className="panel left-panel script-manager-panel classic-file-manager">
      <div className="panel-title">脚本文件管理</div>
      <div className="file-manager-pathbar classic-pathbar">
        <span className="path-label">位置:</span>
        <button className="path-crumb" onClick={() => onOpenDir('')} title="返回脚本根目录">/</button>
        {crumbs.map((part, index) => {
          const path = crumbs.slice(0, index + 1).join('/')
          return (
            <span key={path} className="path-part">
              <span className="path-sep">/</span>
              <button className="path-crumb" onClick={() => onOpenDir(path)} title={path}>{part}</button>
            </span>
          )
        })}
      </div>
      <div className="classic-toolbar-row">
        <button onClick={onReload} title="刷新当前目录">刷新</button>
        <button onClick={onNewSingleScript} title="在根目录新建 .js 单文件脚本">新建单脚本</button>
        <button onClick={onNewMultiScript} title="新建 文件夹/index.js 多文件脚本">新建多脚本</button>
      </div>
      <div className="classic-toolbar-row">
        <button onClick={() => onNewFile(currentDir || undefined)} title="在当前目录新建文件">新建文件</button>
        <button onClick={() => onNewFolder(currentDir || undefined)} title="在当前目录新建文件夹">新建文件夹</button>
        <input
          type="text"
          value={query}
          placeholder="过滤当前目录"
          onChange={(ev) => setQuery(ev.target.value)}
        />
      </div>
      <div className="small muted script-manager-hint">
        根目录 <code>*.js</code> 和 <code>目录/index.js</code> 是有效入口；双击 <code>..</code> 返回上级；右键文件/文件夹可编辑、改名、删除。
      </div>

      <div className="classic-file-table" role="table" aria-label="脚本文件" onContextMenu={(ev) => ev.preventDefault()}>
        <div className="classic-file-row classic-file-head" role="row">
          <div>名称</div>
          <div>类型</div>
          <div>大小</div>
          <div>修改时间</div>
          <div>操作</div>
        </div>
        {canGoUp ? (
          <div className="classic-file-row directory up-entry" role="row" onDoubleClick={onGoUp} title="双击返回上级目录">
            <div className="classic-name"><span className="file-icon">📁</span><span>..</span></div>
            <div>上级目录</div>
            <div />
            <div />
            <div className="muted">双击</div>
          </div>
        ) : null}
        {filteredEntries.map((entry) => {
          const isDir = entry.type === 'directory'
          const script = scriptByPath.get(entry.path)
          const isCurrent = currentPath === entry.path
          const role = scriptRoleLabel(entry)
          return (
            <div
              key={`${entry.type}:${entry.path}`}
              className={`classic-file-row ${entry.type}${isCurrent ? ' selected' : ''}${role ? ' script-role' : ''}`}
              role="row"
              title={entry.path || entry.name}
              onDoubleClick={() => openEntry(entry)}
              onContextMenu={(ev) => showEntryMenu(ev, entry)}
              onClick={() => {
                if (!isDir && entry.extension === 'js') onOpenScript(entry.path)
              }}
            >
              <div className="classic-name">
                <span className="file-icon">{isDir ? '📁' : '📄'}</span>
                <span className="file-name">{entry.name}</span>
                {role ? <span className="tree-badge">{role}</span> : null}
              </div>
              <div>{isDir ? '文件夹' : entry.extension ? `.${entry.extension}` : '文件'}</div>
              <div>{!isDir ? formatSize(entry.size) : ''}</div>
              <div>{formatTime(entry.modifiedAt)}</div>
              <div className="classic-file-actions">
                <button onClick={(ev) => { ev.stopPropagation(); openEntry(entry) }}>{isDir ? '打开' : '编辑'}</button>
                <button onClick={(ev) => { ev.stopPropagation(); onRenamePath(entry.path) }}>改名</button>
                <button onClick={(ev) => { ev.stopPropagation(); onDeletePath(entry.path) }}>删除</button>
              </div>
              {script ? <div className="classic-script-caption">{script.name || script.id || ''}</div> : null}
            </div>
          )
        })}
        {!filteredEntries.length ? <div className="classic-empty small muted">当前目录没有文件。</div> : null}
      </div>

      {menu ? (
        <div
          className="file-context-menu"
          style={{ left: menu.x, top: menu.y }}
          onClick={(ev) => ev.stopPropagation()}
          onContextMenu={(ev) => ev.preventDefault()}
        >
          <button onClick={() => { openEntry(menu.entry); setMenu(null) }}>{menu.entry.type === 'directory' ? '打开' : '编辑'}</button>
          <button onClick={() => { onRenamePath(menu.entry.path); setMenu(null) }}>改名</button>
          <button className="danger" onClick={() => { onDeletePath(menu.entry.path); setMenu(null) }}>删除</button>
        </div>
      ) : null}
    </aside>
  )
}
