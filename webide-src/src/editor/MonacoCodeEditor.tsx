import { useEffect, useRef } from 'react'
import * as monaco from 'monaco-editor'
import { setupMonacoWorkers } from './monacoWorkers'

setupMonacoWorkers()

interface Props {
  value: string
  path?: string | null
  breakpoints?: number[]
  pausedLine?: number | null
  onChange: (value: string) => void
  onSave: () => void
  onToggleBreakpoint?: (line: number) => void
}

export function MonacoCodeEditor({
  value,
  path,
  breakpoints = [],
  pausedLine,
  onChange,
  onSave,
  onToggleBreakpoint
}: Props) {
  const hostRef = useRef<HTMLDivElement | null>(null)
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null)
  const modelRef = useRef<monaco.editor.ITextModel | null>(null)
  const breakpointDecorationsRef = useRef<monaco.editor.IEditorDecorationsCollection | null>(null)
  const pausedDecorationsRef = useRef<monaco.editor.IEditorDecorationsCollection | null>(null)
  const suppressChangeRef = useRef(false)
  const onSaveRef = useRef(onSave)
  const onToggleBreakpointRef = useRef(onToggleBreakpoint)

  useEffect(() => {
    onSaveRef.current = onSave
  }, [onSave])

  useEffect(() => {
    onToggleBreakpointRef.current = onToggleBreakpoint
  }, [onToggleBreakpoint])

  useEffect(() => {
    if (!hostRef.current) return

    monaco.languages.typescript.javascriptDefaults.setDiagnosticsOptions({
      noSemanticValidation: false,
      noSyntaxValidation: false
    })
    monaco.languages.typescript.javascriptDefaults.setCompilerOptions({
      allowNonTsExtensions: true,
      target: monaco.languages.typescript.ScriptTarget.ES2020,
      checkJs: false
    })

    modelRef.current = monaco.editor.createModel(value || '', 'javascript', monaco.Uri.parse(`file:///${path || 'script.js'}`))
    editorRef.current = monaco.editor.create(hostRef.current, {
      model: modelRef.current,
      theme: 'vs',
      fontFamily: "Consolas, 'Courier New', monospace",
      fontSize: 13,
      lineHeight: 19,
      tabSize: 2,
      insertSpaces: true,
      detectIndentation: false,
      automaticLayout: true,
      minimap: { enabled: false },
      scrollBeyondLastLine: false,
      renderWhitespace: 'selection',
      wordWrap: 'off',
      folding: true,
      glyphMargin: true,
      lineNumbersMinChars: 4,
      bracketPairColorization: { enabled: true },
      quickSuggestions: true,
      suggestOnTriggerCharacters: true,
      acceptSuggestionOnEnter: 'on',
      formatOnPaste: false,
      formatOnType: false,
      contextmenu: true
    })

    breakpointDecorationsRef.current = editorRef.current.createDecorationsCollection()
    pausedDecorationsRef.current = editorRef.current.createDecorationsCollection()

    const saveAction = editorRef.current.addAction({
      id: 'xhh-save-script',
      label: '保存脚本',
      keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS],
      run: () => onSaveRef.current()
    })

    const sub = modelRef.current.onDidChangeContent(() => {
      if (suppressChangeRef.current) return
      onChange(modelRef.current?.getValue() || '')
    })

    const mouseSub = editorRef.current.onMouseDown((event) => {
      const targetType = event.target.type
      if (
        targetType === monaco.editor.MouseTargetType.GUTTER_GLYPH_MARGIN ||
        targetType === monaco.editor.MouseTargetType.GUTTER_LINE_NUMBERS ||
        targetType === monaco.editor.MouseTargetType.GUTTER_LINE_DECORATIONS
      ) {
        const line = event.target.position?.lineNumber
        if (line && line > 0) {
          event.event.preventDefault()
          event.event.stopPropagation()
          onToggleBreakpointRef.current?.(line)
        }
      }
    })

    return () => {
      saveAction.dispose()
      sub.dispose()
      mouseSub.dispose()
      breakpointDecorationsRef.current?.clear()
      pausedDecorationsRef.current?.clear()
      editorRef.current?.dispose()
      modelRef.current?.dispose()
      editorRef.current = null
      modelRef.current = null
    }
  }, [])

  useEffect(() => {
    const model = modelRef.current
    if (!model) return
    if (model.getValue() === value) return
    suppressChangeRef.current = true
    model.setValue(value || '')
    suppressChangeRef.current = false
  }, [value])

  useEffect(() => {
    const editor = editorRef.current
    const model = modelRef.current
    if (!editor || !model) return
    const maxLine = model.getLineCount()
    const unique = Array.from(new Set((breakpoints || []).filter((line) => line > 0 && line <= maxLine))).sort((a, b) => a - b)
    breakpointDecorationsRef.current?.set(
      unique.map((line) => ({
        range: new monaco.Range(line, 1, line, 1),
        options: {
          isWholeLine: false,
          glyphMarginClassName: 'xhh-breakpoint-glyph',
          glyphMarginHoverMessage: { value: `行断点：${line}` },
          overviewRuler: {
            position: monaco.editor.OverviewRulerLane.Left,
            color: '#d32f2f'
          }
        }
      }))
    )
  }, [breakpoints, value])

  useEffect(() => {
    const editor = editorRef.current
    const model = modelRef.current
    if (!editor || !model) return
    const line = pausedLine || 0
    if (line > 0 && line <= model.getLineCount()) {
      pausedDecorationsRef.current?.set([
        {
          range: new monaco.Range(line, 1, line, 1),
          options: {
            isWholeLine: true,
            className: 'xhh-paused-line',
            linesDecorationsClassName: 'xhh-current-line-arrow',
            hoverMessage: { value: `当前暂停行：${line}` }
          }
        }
      ])
      editor.revealLineInCenterIfOutsideViewport(line)
    } else {
      pausedDecorationsRef.current?.clear()
    }
  }, [pausedLine, path])

  return <div className="monaco-host" ref={hostRef} />
}
