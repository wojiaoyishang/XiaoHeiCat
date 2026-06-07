import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api } from './api/client'
import { Icon } from './components/Icon'
import type { ApiStatus } from './types/webide'

interface LogLine {
  id: number
  text: string
  type?: 'ok' | 'err' | 'warn' | 'target'
}

let logPageId = 1

function readInitialPackageName() {
  return new URLSearchParams(window.location.search).get('packageName') || ''
}

function classifyLogLine(text: string, fallback?: string) {
  if (fallback === 'err' || /\sE\//.test(text) || /\bERROR\b/i.test(text)) return 'log-error'
  if (fallback === 'warn' || /\sW\//.test(text) || /\bWARN(?:ING)?\b/i.test(text)) return 'log-warn'
  if (fallback === 'ok' || /\sI\//.test(text) || /\bINFO\b/i.test(text)) return 'log-info'
  if (/\sD\//.test(text) || /\bDEBUG\b/i.test(text)) return 'log-debug'
  if (/\sV\//.test(text) || /\bVERBOSE\b/i.test(text)) return 'log-verbose'
  if (fallback === 'target') return 'log-target'
  return fallback || ''
}

function matchesKeyword(text: string, keyword: string) {
  const q = keyword.trim().toLowerCase()
  if (!q) return true
  return text.toLowerCase().includes(q)
}

function matchesLevel(text: string, level: string, type?: string) {
  if (!level || level === 'all') return true
  const cls = classifyLogLine(text, type)
  return cls === `log-${level}`
}

export function LogsPage() {
  const [status, setStatus] = useState<ApiStatus | null>(null)
  const [packageName, setPackageName] = useState(readInitialPackageName)
  const [connectedPackage, setConnectedPackage] = useState(readInitialPackageName)
  const [lines, setLines] = useState<LogLine[]>([])
  const [connected, setConnected] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [level, setLevel] = useState('all')
  const [followLogs, setFollowLogs] = useState(true)
  const consoleRef = useRef<HTMLPreElement | null>(null)

  const append = useCallback((text: string, type?: LogLine['type']) => {
    const now = new Date().toLocaleTimeString()
    setLines((old) => {
      const next = [...old, { id: logPageId++, text: `[${now}] ${text}`, type }]
      return next.length > 10000 ? next.slice(next.length - 10000) : next
    })
  }, [])

  const visibleLines = useMemo(
    () => lines.filter((line) => matchesKeyword(line.text, keyword) && matchesLevel(line.text, level, line.type)),
    [lines, keyword, level]
  )

  const clear = useCallback(() => setLines([]), [])

  useEffect(() => {
    document.title = 'XiaoHeiHook 日志'
    api<ApiStatus>('/api/status')
      .then(setStatus)
      .catch((e) => append(`状态读取失败：${e?.message || e}`, 'err'))
  }, [append])

  useEffect(() => {
    const el = consoleRef.current
    if (el && followLogs) {
      el.scrollTop = el.scrollHeight
    }
  }, [visibleLines, followLogs])

  const handleConsoleScroll = useCallback(() => {
    const el = consoleRef.current
    if (!el || !followLogs) return
    const distanceToBottom = el.scrollHeight - el.scrollTop - el.clientHeight
    if (distanceToBottom > 24) {
      setFollowLogs(false)
    }
  }, [followLogs])

  const enableFollowLogs = useCallback(() => {
    setFollowLogs(true)
    requestAnimationFrame(() => {
      const el = consoleRef.current
      if (el) el.scrollTop = el.scrollHeight
    })
  }, [])

  useEffect(() => {
    if (!connectedPackage.trim()) {
      setConnected(false)
      return
    }

    const pkg = connectedPackage.trim()
    setConnected(false)
    append(`正在连接日志：${pkg}`)

    api<{ ok: boolean; lines?: string[]; text?: string }>(`/api/logs?packageName=${encodeURIComponent(pkg)}&maxLines=2000`)
      .then((data) => {
        const initialLines = data.lines?.length ? data.lines : (data.text || '').split(/\r?\n/)
        initialLines.filter(Boolean).forEach((line) => append(line, 'target'))
      })
      .catch((e) => append(`读取历史日志失败：${e?.message || e}`, 'warn'))

    const source = new EventSource(`/api/logs/stream?packageName=${encodeURIComponent(pkg)}`)

    source.addEventListener('open', () => {
      setConnected(true)
      append(`实时日志已连接：${pkg}`, 'ok')
    })

    source.addEventListener('log', (event) => {
      try {
        const data = JSON.parse((event as MessageEvent).data) as { line?: string }
        if (data.line) append(data.line, 'target')
      } catch {
        const text = (event as MessageEvent).data
        if (text) append(text, 'target')
      }
    })

    source.addEventListener('error', () => {
      setConnected(false)
      append(`实时日志连接中断，浏览器会自动重连：${pkg}`, 'warn')
    })

    return () => {
      source.close()
      setConnected(false)
    }
  }, [connectedPackage, append])

  const connect = useCallback(() => {
    const pkg = packageName.trim()
    if (!pkg) {
      append('请输入包名后再连接', 'warn')
      return
    }
    const url = new URL(window.location.href)
    url.searchParams.set('packageName', pkg)
    window.history.replaceState(null, '', url.toString())
    clear()
    setConnectedPackage(pkg)
  }, [packageName, append, clear])

  return (
    <div className="logs-page">
      <div className="logs-toolbar">
        <b>XiaoHeiHook 日志</b>
        <input
          type="text"
          value={packageName}
          placeholder="目标包名，例如 cn.am7code.tools"
          onChange={(ev) => setPackageName(ev.target.value)}
          onKeyDown={(ev) => {
            if (ev.key === 'Enter') connect()
          }}
        />
        <button onClick={connect} title="连接指定应用日志"><Icon name="terminal" /> 连接</button>
        <input
          type="text"
          value={keyword}
          placeholder="关键词过滤..."
          onChange={(ev) => setKeyword(ev.target.value)}
        />
        <select value={level} onChange={(ev) => setLevel(ev.target.value)} title="按日志等级过滤">
          <option value="all">全部等级</option>
          <option value="error">Error</option>
          <option value="warn">Warn</option>
          <option value="info">Info</option>
          <option value="debug">Debug</option>
          <option value="verbose">Verbose</option>
          <option value="target">Target</option>
        </select>
        <button className={followLogs ? 'active' : ''} onClick={enableFollowLogs} title="跟踪最新日志；手动滚动会暂停自动滚动">跟踪</button>
        <button onClick={clear} title="清空当前日志"><Icon name="clear" /> 清空</button>
        <button onClick={() => window.location.href = '/'} title="返回 WebIDE"><Icon name="apps" /> 返回 IDE</button>
        <span className={connected ? 'terminal-state ok' : 'terminal-state warn'}>{connected ? '已连接' : '未连接'}</span>
        <span className="terminal-status">{visibleLines.length}/{lines.length} 行</span>
      </div>
      <pre ref={consoleRef} className="logs-console" onScroll={handleConsoleScroll}>
        {visibleLines.map((line) => (
          <div key={line.id} className={`${line.type || ''} ${classifyLogLine(line.text, line.type)}`}>{line.text}</div>
        ))}
      </pre>
    </div>
  )
}
