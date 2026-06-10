import { useEffect, useMemo, useState, type MouseEvent as ReactMouseEvent } from 'react'
import type { ScriptFileEntry, ScriptInfo } from '../types/webide'



type FileColumnKey = 'name' | 'type' | 'size' | 'modified' | 'actions'

const FILE_COLUMN_STORAGE_KEY = 'xhh.scriptManager.columnWidths'

const FILE_COLUMNS: Array<{ key: FileColumnKey; label: string; min: number; defaultWidth: number }> = [
  { key: 'name', label: '名称', min: 120, defaultWidth: 210 },
  { key: 'type', label: '类型', min: 48, defaultWidth: 64 },
  { key: 'size', label: '大小', min: 54, defaultWidth: 70 },
  { key: 'modified', label: '修改时间', min: 96, defaultWidth: 132 },
  { key: 'actions', label: '操作', min: 92, defaultWidth: 118 },
]

type FileColumnWidths = Record<FileColumnKey, number>

function defaultFileColumnWidths(): FileColumnWidths {
  return FILE_COLUMNS.reduce((acc, column) => {
    acc[column.key] = column.defaultWidth
    return acc
  }, {} as FileColumnWidths)
}

function loadFileColumnWidths(): FileColumnWidths {
  const defaults = defaultFileColumnWidths()
  try {
    const raw = window.localStorage.getItem(FILE_COLUMN_STORAGE_KEY)
    if (!raw) return defaults
    const parsed = JSON.parse(raw) as Partial<Record<FileColumnKey, number>>
    return FILE_COLUMNS.reduce((acc, column) => {
      const value = Number(parsed[column.key])
      acc[column.key] = Number.isFinite(value) ? Math.max(column.min, value) : defaults[column.key]
      return acc
    }, {} as FileColumnWidths)
  } catch {
    return defaults
  }
}

function saveFileColumnWidths(widths: FileColumnWidths) {
  try {
    window.localStorage.setItem(FILE_COLUMN_STORAGE_KEY, JSON.stringify(widths))
  } catch {
    // ignore localStorage errors in private mode / restricted WebView
  }
}

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
  const [columnWidths, setColumnWidths] = useState<FileColumnWidths>(() => loadFileColumnWidths())
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

  const columnTemplate = FILE_COLUMNS.map((column) => `${columnWidths[column.key]}px`).join(' ')

  const startResizeColumn = (ev: ReactMouseEvent, key: FileColumnKey) => {
    ev.preventDefault()
    ev.stopPropagation()
    const column = FILE_COLUMNS.find((item) => item.key === key)
    if (!column) return

    const startX = ev.clientX
    const startWidth = columnWidths[key]
    document.body.classList.add('resizing-file-column')

    const onMove = (moveEv: MouseEvent) => {
      const nextWidth = Math.max(column.min, startWidth + moveEv.clientX - startX)
      setColumnWidths((old) => ({ ...old, [key]: nextWidth }))
    }

    const onUp = (upEv: MouseEvent) => {
      const nextWidth = Math.max(column.min, startWidth + upEv.clientX - startX)
      const next = { ...columnWidths, [key]: nextWidth }
      setColumnWidths(next)
      saveFileColumnWidths(next)
      document.body.classList.remove('resizing-file-column')
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
    }

    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }

  const resetColumnWidth = (key: FileColumnKey) => {
    const column = FILE_COLUMNS.find((item) => item.key === key)
    if (!column) return
    const next = { ...columnWidths, [key]: column.defaultWidth }
    setColumnWidths(next)
    saveFileColumnWidths(next)
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
        <div className="classic-file-row classic-file-head" role="row" style={{ gridTemplateColumns: columnTemplate }}>
          {FILE_COLUMNS.map((column) => (
            <div
              key={column.key}
              className="classic-file-head-cell"
              role="columnheader"
              title="拖动右侧边界调整宽度，双击恢复默认宽度"
              onDoubleClick={() => resetColumnWidth(column.key)}
            >
              <span>{column.label}</span>
              <span
                className="classic-file-column-resizer"
                onMouseDown={(ev) => startResizeColumn(ev, column.key)}
              />
            </div>
          ))}
        </div>
        {canGoUp ? (
          <div className="classic-file-row directory up-entry" role="row" style={{ gridTemplateColumns: columnTemplate }} onDoubleClick={onGoUp} title="双击返回上级目录">
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
              style={{ gridTemplateColumns: columnTemplate }}
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
