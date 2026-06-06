import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Icon } from './Icon'
import type { DebugEvent } from '../types/webide'

export interface ConsoleLine {
  id: number
  text: string
  type?: 'ok' | 'err' | 'warn' | 'target'
}

interface Props {
  lines: ConsoleLine[]
  debugEvents: DebugEvent[]
  debugEnabled: boolean
  livePackage?: string | null
  height: number
  onHeightChange: (height: number) => void
  onClear: () => void
  onOpenStandalone: () => void
  onHide: () => void
  onDebugEnabledChange: (enabled: boolean) => void
  onDebugContinue: (event: DebugEvent, returnValue?: { enabled: boolean; text: string }) => void
  onDebugAbort: (event: DebugEvent) => void
  onDebugApplyLocals: (event: DebugEvent, localsJson: string) => void
  onDebugSetVariable: (event: DebugEvent, path: string, valueJson: string) => void
}

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value))
}

function formatTime(value?: number) {
  if (!value) return ''
  try {
    return new Date(value).toLocaleTimeString()
  } catch {
    return ''
  }
}

function pretty(value: unknown) {
  if (value === null || value === undefined) return 'null'
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

export function BottomConsole({
  lines,
  debugEvents,
  debugEnabled,
  livePackage,
  height,
  onHeightChange,
  onClear,
  onOpenStandalone,
  onHide,
  onDebugEnabledChange,
  onDebugContinue,
  onDebugAbort,
  onDebugApplyLocals,
  onDebugSetVariable
}: Props) {
  const [tab, setTab] = useState<'output' | 'debug'>('output')
  const [localsJson, setLocalsJson] = useState('')
  const [variablePath, setVariablePath] = useState('')
  const [variableValueJson, setVariableValueJson] = useState('null')
  const [returnEnabled, setReturnEnabled] = useState(false)
  const [returnValueText, setReturnValueText] = useState('null')
  const consoleRef = useRef<HTMLPreElement | null>(null)
  const debugRef = useRef<HTMLDivElement | null>(null)
  const dragStateRef = useRef<{ startY: number; startHeight: number } | null>(null)

  const pausedEvents = useMemo(
    () => debugEvents.filter((event) => event.type === 'paused' && event.pauseId),
    [debugEvents]
  )
  const latestPaused = pausedEvents[pausedEvents.length - 1] || null

  useEffect(() => {
    const el = consoleRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [lines, tab])

  useEffect(() => {
    const el = debugRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [debugEvents, tab])

  useEffect(() => {
    if (debugEvents.some((event) => event.type === 'paused')) {
      setTab('debug')
    }
  }, [debugEvents])

  useEffect(() => {
    if (!latestPaused) {
      setLocalsJson('')
      setVariablePath('')
      setVariableValueJson('null')
      setReturnEnabled(false)
      setReturnValueText('null')
      return
    }
    setLocalsJson(pretty(latestPaused.locals))
    setReturnEnabled(false)
    setReturnValueText('null')
    const locals = latestPaused.locals as any
    if (locals && typeof locals === 'object' && !Array.isArray(locals)) {
      const firstKey = Object.keys(locals)[0]
      if (firstKey) {
        setVariablePath(firstKey)
        setVariableValueJson(pretty(locals[firstKey]))
      }
    }
  }, [latestPaused?.pauseId, latestPaused?.locals])

  const onMouseMove = useCallback((ev: MouseEvent) => {
    const drag = dragStateRef.current
    if (!drag) return
    const max = Math.max(180, window.innerHeight - 72)
    const next = clamp(drag.startHeight - (ev.clientY - drag.startY), 96, max)
    onHeightChange(next)
  }, [onHeightChange])

  const stopDrag = useCallback(() => {
    dragStateRef.current = null
    document.body.classList.remove('console-resizing')
    window.removeEventListener('mousemove', onMouseMove)
    window.removeEventListener('mouseup', stopDrag)
  }, [onMouseMove])

  const startDrag = useCallback((ev: React.MouseEvent<HTMLDivElement>) => {
    ev.preventDefault()
    dragStateRef.current = { startY: ev.clientY, startHeight: height }
    document.body.classList.add('console-resizing')
    window.addEventListener('mousemove', onMouseMove)
    window.addEventListener('mouseup', stopDrag)
  }, [height, onMouseMove, stopDrag])

  useEffect(() => stopDrag, [stopDrag])

  return (
    <section className="bottom" style={{ height }}>
      <div className="console-resizer" title="拖动调整终端高度" onMouseDown={startDrag} />
      <div className="console-head">
        <div className="console-tabs">
          <button className={tab === 'output' ? 'active' : ''} onClick={() => setTab('output')} title="控制台输出">输出</button>
          <button className={tab === 'debug' ? 'active' : ''} onClick={() => setTab('debug')} title="软断点调试">
            调试{pausedEvents.length ? ` (${pausedEvents.length})` : ''}
          </button>
        </div>
        {livePackage ? <span className="console-live">实时日志：{livePackage}</span> : <span className="console-live muted">未选择应用</span>}
        <div className="console-actions">
          <button onClick={onOpenStandalone} title={livePackage ? `单独打开终端：${livePackage}` : '单独打开终端'}><Icon name="terminal" /> 单开</button>
          <button onClick={onClear} title="清空终端"><Icon name="clear" /> 清空</button>
          <button onClick={onHide} title="隐藏下方终端 Ctrl+Alt+T"><Icon name="hide" /> 隐藏</button>
        </div>
      </div>

      {tab === 'output' ? (
        <pre ref={consoleRef} className="console">
          {lines.map((line) => (
            <div key={line.id} className={line.type || ''}>{line.text}</div>
          ))}
        </pre>
      ) : (
        <div ref={debugRef} className="debug-panel">
          <div className="debug-mode-bar">
            <label className="debug-switch" title="关闭后 debuggerx.breakpoint 会直接忽略，不会暂停目标应用">
              <input
                type="checkbox"
                checked={debugEnabled}
                disabled={!livePackage}
                onChange={(ev) => onDebugEnabledChange(ev.target.checked)}
              />
              <span className="debug-switch-title">启用软断点调试</span>
            </label>
            <span className={debugEnabled ? 'debug-mode-on' : 'debug-mode-off'}>
              {debugEnabled ? '断点会暂停目标线程' : '断点已忽略，普通运行不会卡住'}
            </span>
          </div>
          {!debugEvents.length ? (
            <div className="debug-empty">
              在脚本中调用 <code>debuggerx.breakpoint('name', {'{ value }'})</code> 后，命中断点会显示在这里。
            </div>
          ) : null}

          {latestPaused ? (
            <div className="debug-current">
              <div className="debug-title">Paused: {latestPaused.breakpointName || latestPaused.pauseId}</div>
              <div className="debug-meta">
                {latestPaused.packageName} / {latestPaused.processName} / {latestPaused.scriptName} / {latestPaused.threadName}
              </div>
              <div className="debug-actions">
                <button
                  className="primary"
                  onClick={() => onDebugContinue(latestPaused, { enabled: returnEnabled, text: returnValueText })}
                  title="继续执行；启用返回值后 debuggerx.breakpoint(...) 会返回右侧 JSON 值"
                >
                  Continue
                </button>
                <button onClick={() => onDebugAbort(latestPaused)}>Abort</button>
              </div>

              <div className="debug-edit-grid">
                <div className="debug-edit-card">
                  <div className="debug-section-title">查看 / 修改 locals</div>
                  <textarea
                    className="debug-json-editor"
                    value={localsJson}
                    spellCheck={false}
                    onChange={(ev) => setLocalsJson(ev.target.value)}
                  />
                  <button onClick={() => onDebugApplyLocals(latestPaused, localsJson)} title="把当前 JSON 对象写回 debuggerx.breakpoint(name, locals) 传入的 locals 对象">
                    应用 locals 修改
                  </button>
                  <div className="debug-note">仅能修改传给 breakpoint 的 locals 对象/数组/Map；普通 JS 局部变量需要脚本在继续后主动读取 locals。</div>
                </div>

                <div className="debug-edit-card compact">
                  <div className="debug-section-title">按路径修改变量</div>
                  <input
                    className="debug-input"
                    value={variablePath}
                    placeholder="例如: value 或 args[0]"
                    onChange={(ev) => setVariablePath(ev.target.value)}
                  />
                  <textarea
                    className="debug-value-editor"
                    value={variableValueJson}
                    spellCheck={false}
                    onChange={(ev) => setVariableValueJson(ev.target.value)}
                  />
                  <button onClick={() => onDebugSetVariable(latestPaused, variablePath, variableValueJson)}>设置变量</button>

                  <div className="debug-section-title return-title">返回值给流程</div>
                  <label className="debug-return-toggle">
                    <input type="checkbox" checked={returnEnabled} onChange={(ev) => setReturnEnabled(ev.target.checked)} />
                    <span>Continue 时让 breakpoint 返回 JSON 值</span>
                  </label>
                  <textarea
                    className="debug-value-editor"
                    value={returnValueText}
                    spellCheck={false}
                    onChange={(ev) => setReturnValueText(ev.target.value)}
                  />
                </div>
              </div>
            </div>
          ) : null}

          <div className="debug-events">
            {debugEvents.slice(-100).map((event, index) => (
              <div key={`${event.pauseId || event.type}-${index}`} className={`debug-event ${event.type}`}>
                <span className="debug-event-type">{event.type}</span>
                <span>{formatTime(event.time)}</span>
                <span>{event.packageName || '-'}</span>
                <span>{event.breakpointName || event.message || event.pauseId || '-'}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </section>
  )
}
