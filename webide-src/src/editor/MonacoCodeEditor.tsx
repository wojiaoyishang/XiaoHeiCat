import { useEffect, useRef } from 'react'
import * as monaco from 'monaco-editor'
import { setupMonacoWorkers } from './monacoWorkers'

setupMonacoWorkers()

interface Props {
  value: string
  onChange: (value: string) => void
  onSave: () => void
}

export function MonacoCodeEditor({ value, onChange, onSave }: Props) {
  const hostRef = useRef<HTMLDivElement | null>(null)
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null)
  const modelRef = useRef<monaco.editor.ITextModel | null>(null)
  const suppressChangeRef = useRef(false)
  const onSaveRef = useRef(onSave)

  useEffect(() => {
    onSaveRef.current = onSave
  }, [onSave])

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

    modelRef.current = monaco.editor.createModel(value || '', 'javascript', monaco.Uri.parse('file:///script.js'))
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
      bracketPairColorization: { enabled: true },
      quickSuggestions: true,
      suggestOnTriggerCharacters: true,
      acceptSuggestionOnEnter: 'on',
      formatOnPaste: false,
      formatOnType: false,
      contextmenu: true
    })

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

    return () => {
      saveAction.dispose()
      sub.dispose()
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

  return <div className="monaco-host" ref={hostRef} />
}
