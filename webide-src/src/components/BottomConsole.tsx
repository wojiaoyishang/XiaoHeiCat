import { useCallback, useEffect, useRef } from 'react'
import { Icon } from './Icon'

export interface ConsoleLine {
  id: number
  text: string
  type?: 'ok' | 'err' | 'warn' | 'target'
}

interface Props {
  lines: ConsoleLine[]
  livePackage?: string | null
  height: number
  onHeightChange: (height: number) => void
  onClear: () => void
  onOpenStandalone: () => void
  onHide: () => void
}

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value))
}

export function BottomConsole({ lines, livePackage, height, onHeightChange, onClear, onOpenStandalone, onHide }: Props) {
  const consoleRef = useRef<HTMLPreElement | null>(null)
  const dragStateRef = useRef<{ startY: number; startHeight: number } | null>(null)

  useEffect(() => {
    const el = consoleRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [lines])

  const onMouseMove = useCallback((ev: MouseEvent) => {
    const drag = dragStateRef.current
    if (!drag) return
    const max = Math.max(180, window.innerHeight - 40)
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
        <span>输出</span>
        {livePackage ? <span className="console-live">实时日志：{livePackage}</span> : <span className="console-live muted">未选择应用</span>}
        <div className="console-actions">
          <button onClick={onOpenStandalone} title={livePackage ? `单独打开终端：${livePackage}` : '单独打开终端'}><Icon name="terminal" /> 单开</button>
          <button onClick={onClear} title="清空终端"><Icon name="clear" /> 清空</button>
          <button onClick={onHide} title="隐藏下方终端 Ctrl+Alt+T"><Icon name="hide" /> 隐藏</button>
        </div>
      </div>
      <pre ref={consoleRef} className="console">
        {lines.map((line) => (
          <div key={line.id} className={line.type || ''}>{line.text}</div>
        ))}
      </pre>
    </section>
  )
}
