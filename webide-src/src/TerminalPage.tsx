import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from './api/client'
import { Icon } from './components/Icon'
import type { ApiStatus } from './types/webide'

interface TerminalLine {
  id: number
  text: string
  type?: 'ok' | 'err' | 'warn' | 'target'
}

let terminalLogId = 1

function readInitialPackageName() {
  return new URLSearchParams(window.location.search).get('packageName') || ''
}

export function TerminalPage() {
  const [status, setStatus] = useState<ApiStatus | null>(null)
  const [packageName, setPackageName] = useState(readInitialPackageName)
  const [connectedPackage, setConnectedPackage] = useState(readInitialPackageName)
  const [lines, setLines] = useState<TerminalLine[]>([])
  const [connected, setConnected] = useState(false)
  const consoleRef = useRef<HTMLPreElement | null>(null)

  const append = useCallback((text: string, type?: TerminalLine['type']) => {
    const now = new Date().toLocaleTimeString()
    setLines((old) => {
      const next = [...old, { id: terminalLogId++, text: `[${now}] ${text}`, type }]
      return next.length > 5000 ? next.slice(next.length - 5000) : next
    })
  }, [])

  const clear = useCallback(() => setLines([]), [])

  useEffect(() => {
    document.title = 'XiaoHeiHook 终端'
    api<ApiStatus>('/api/status')
      .then(setStatus)
      .catch((e) => append(`状态读取失败：${e?.message || e}`, 'err'))
  }, [append])

  useEffect(() => {
    const el = consoleRef.current
    if (el) el.scrollTop = 0
  }, [lines])

  useEffect(() => {
    if (!connectedPackage.trim()) {
      setConnected(false)
      return
    }

    const pkg = connectedPackage.trim()
    setConnected(false)
    append(`正在连接实时日志：${pkg}`)

    api<{ ok: boolean; lines?: string[]; text?: string }>(`/api/logs?packageName=${encodeURIComponent(pkg)}&maxLines=1000`)
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
    <div className="terminal-page">
      <div className="terminal-toolbar">
        <b>XiaoHeiHook 终端</b>
        <input
          type="text"
          value={packageName}
          placeholder="输入目标包名，例如 cn.am7code.tools"
          onChange={(ev) => setPackageName(ev.target.value)}
          onKeyDown={(ev) => {
            if (ev.key === 'Enter') connect()
          }}
        />
        <button onClick={connect} title="连接指定应用日志"><Icon name="terminal" /> 连接</button>
        <button onClick={clear} title="清空当前终端"><Icon name="clear" /> 清空</button>
        <button onClick={() => window.location.href = '/'} title="返回 WebIDE"><Icon name="apps" /> 返回 IDE</button>
        <span className={connected ? 'terminal-state ok' : 'terminal-state warn'}>{connected ? '已连接' : '未连接'}</span>
        <span className="terminal-status">{status ? `${status.server || 'WebIDE'} | ${status.host}:${status.port}` : '读取状态中'}</span>
      </div>
      <pre ref={consoleRef} className="terminal-console">
        {[...lines].reverse().map((line) => (
          <div key={line.id} className={line.type || ''}>{line.text}</div>
        ))}
      </pre>
    </div>
  )
}
