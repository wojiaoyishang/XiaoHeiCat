import { useMemo, useState } from 'react'

export interface GeneratedScript {
  suggestedPath: string
  content: string
}

type HookPoint = 'intercept' | 'after' | 'before'

interface FormState {
  className: string
  methodName: string
  params: string
  returnClassName: string
  modifyResult: boolean
  resultValues: string
  hookPoint: HookPoint
  logStaticFields: boolean
  logSampleVars: boolean
  hookContextFirst: boolean
}

interface Props {
  selectedPackage?: string | null
  onGenerate: (script: GeneratedScript) => void
  onClose: () => void
}

const initialForm: FormState = {
  className: 'q2.q',
  methodName: 'b',
  params: 'android.content.Context',
  returnClassName: 'boolean',
  modifyResult: true,
  resultValues: 'true',
  hookPoint: 'after',
  logStaticFields: false,
  logSampleVars: false,
  hookContextFirst: false,
}

function parseParams(params: string) {
  return params
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
}

function safeId(value: string) {
  return value.replace(/[^A-Za-z0-9_.-]/g, '.').replace(/^\.+|\.+$/g, '') || 'generated.hook'
}

function safeName(value: string) {
  return value.replace(/[^A-Za-z0-9_.-]/g, '_').replace(/^_+|_+$/g, '') || 'generated_hook'
}

function valueExpression(value: string, returnClassName: string) {
  const clean = value.trim()
  if (!clean) return 'null'
  const lowerType = returnClassName.trim().toLowerCase()
  if ((lowerType === 'java.lang.string' || lowerType === 'string') && !/^['"`]/.test(clean)) {
    return JSON.stringify(clean)
  }
  return clean
}

function javaClassExpression(typeName: string) {
  const clean = typeName.trim()
  switch (clean) {
    case 'boolean': return 'Java.type("java.lang.Boolean").TYPE'
    case 'byte': return 'Java.type("java.lang.Byte").TYPE'
    case 'short': return 'Java.type("java.lang.Short").TYPE'
    case 'char': return 'Java.type("java.lang.Character").TYPE'
    case 'int': return 'Java.type("java.lang.Integer").TYPE'
    case 'long': return 'Java.type("java.lang.Long").TYPE'
    case 'float': return 'Java.type("java.lang.Float").TYPE'
    case 'double': return 'Java.type("java.lang.Double").TYPE'
    case 'void': return 'Java.type("java.lang.Void").TYPE'
    default: return `Java.classForName(${JSON.stringify(clean)}, false, loader)`
  }
}

function indentBlock(block: string, spaces = 6) {
  const indent = ' '.repeat(spaces)
  return block
    .split('\n')
    .map((line) => (line.trim() ? indent + line : line))
    .join('\n')
}

function buildGeneratedCode(form: FormState, selectedPackage?: string | null): GeneratedScript {
  const targetPackage = selectedPackage?.trim() || '*'
  const targetClass = form.className.trim()
  const methodName = form.methodName.trim()
  const params = parseParams(form.params)
  const tag = `XHH-GEN-${safeName(methodName || targetClass).slice(0, 28)}`
  const methodParams = params.length ? ', ' + params.map(javaClassExpression).join(', ') : ''
  const returnType = form.returnClassName.trim()
  const returnValue = valueExpression(form.resultValues, returnType)
  const suggestedPath = `generated_${safeName(targetClass)}_${safeName(methodName || 'method')}.js`
  const hookTitle = form.hookPoint === 'before' ? '记录参数值' : form.hookPoint === 'after' ? '只记录返回值' : '拦截执行'

  const preProceedLines: string[] = []
  if (form.logSampleVars) {
    preProceedLines.push('xposed.d(TAG, "this=" + chain.thisObject);')
  }
  if (form.hookPoint === 'before' || form.logSampleVars) {
    preProceedLines.push('const args = chain.getArgs ? chain.getArgs() : [];')
    if (form.hookPoint === 'before' || form.logSampleVars) {
      preProceedLines.push('for (let i = 0; i < args.length; i++) xposed.d(TAG, "arg[" + i + "]=" + args[i]);')
    }
  }

  const postProceedLines: string[] = []
  if (form.hookPoint === 'after' || form.hookPoint === 'intercept') {
    postProceedLines.push('xposed.d(TAG, "result=" + result + (result && result.getClass ? " type=" + result.getClass().getName() : ""));')
  }

  const returnLine = form.modifyResult
    ? returnType
      ? `return Java.to(${JSON.stringify(returnType)}, ${returnValue});`
      : `return ${returnValue};`
    : 'return result;'

  const staticBlock = form.logStaticFields ? `
  const Modifier = Java.type("java.lang.reflect.Modifier");
  const fields = TargetClass.getDeclaredFields();
  for (let i = 0; i < fields.length; i++) {
    const field = fields[i];
    if (!Modifier.isStatic(field.getModifiers())) continue;
    field.setAccessible(true);
    xposed.d(TAG, "static " + field.getName() + "=" + field.get(null));
  }` : ''

  const hookBodyLines = [
    ...preProceedLines,
    'const result = chain.proceed();',
    ...postProceedLines,
    returnLine,
  ]

  const installCall = form.hookContextFirst
    ? `
  // 部分加壳软件需要等 Context.attachBaseContext 后再取 ClassLoader。
  const ContextWrapper = Java.type("android.content.ContextWrapper");
  const Context = Java.type("android.content.Context");
  const attachBaseContext = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
  attachBaseContext.setAccessible(true);

  xposed.hook(attachBaseContext)
    .setPriority(xposed.PRIORITY_DEFAULT)
    .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
    .intercept(function (chain) {
      const result = chain.proceed();
      const ctx = chain.getArg ? chain.getArg(0) : null;
      install(ctx && ctx.getClassLoader ? ctx.getClassLoader() : param.getClassLoader());
      return result;
    });`
    : `
  install(param.getClassLoader());`

  const code = `// ==LSPosedScript==
// @name         ${targetClass || 'Hook'} ${methodName || 'method'} 代码生成器脚本
// @id           ${safeId(`generated.${targetClass}.${methodName}`)}
// @version      1.0.0
// @author       XiaoHeiHook WebIDE
// @description  由 WebIDE 代码生成器生成：${hookTitle}
// @target       ${targetPackage}
// @process      ${targetPackage}
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.full
// ==/LSPosedScript==

// 如果需要捕获全部进程请修改 @process 字段。

const TAG = ${JSON.stringify(tag)};
let installed = false;

function install(loader) {
  if (installed) return;
  installed = true;

  const TargetClass = Java.classForName(${JSON.stringify(targetClass)}, false, loader);
  const method = TargetClass.getDeclaredMethod(${JSON.stringify(methodName)}${methodParams});
  method.setAccessible(true);${staticBlock}

  // =======================================
  // Hook 目标方法
  // =======================================
  xposed.hook(method)
    .setPriority(xposed.PRIORITY_DEFAULT)
    .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
    .intercept(function (chain) {
${indentBlock(hookBodyLines.join('\n'))}
    });

  xposed.d(TAG, "hooked ${targetClass}.${methodName}(${params.join(', ')})");
}

xposed.onPackageLoaded(function (param) {
  xposed.d(TAG, "loaded package=" + param.getPackageName() + ", process=" + env.processName);${installCall}
});
`

  return { suggestedPath, content: code }
}

function formToJson(form: FormState) {
  return JSON.stringify({
    className: form.className,
    methodName: form.methodName,
    params: form.params,
    returnClassName: form.returnClassName,
    resultValues: form.resultValues,
    modifyResult: form.modifyResult,
    hookPoint: form.hookPoint,
    logStaticFields: form.logStaticFields,
    logSampleVars: form.logSampleVars,
    hookContextFirst: form.hookContextFirst,
  })
}

export function CodeGeneratorDialog({ selectedPackage, onGenerate, onClose }: Props) {
  const [form, setForm] = useState<FormState>(initialForm)
  const [quickJson, setQuickJson] = useState(() => formToJson(initialForm))
  const [jsonError, setJsonError] = useState('')

  const generated = useMemo(() => buildGeneratedCode(form, selectedPackage), [form, selectedPackage])

  const update = <K extends keyof FormState>(key: K, value: FormState[K]) => {
    const next = { ...form, [key]: value }
    setForm(next)
    setQuickJson(formToJson(next))
    setJsonError('')
  }

  const importJson = () => {
    try {
      const data = JSON.parse(quickJson || '{}')
      const next: FormState = {
        ...form,
        className: String(data.className ?? form.className),
        methodName: String(data.methodName ?? form.methodName),
        params: String(data.params ?? form.params),
        returnClassName: String(data.returnClassName ?? data.resultType ?? form.returnClassName),
        resultValues: String(data.resultValues ?? data.resultValue ?? form.resultValues),
        modifyResult: data.modifyResult === undefined ? form.modifyResult : Boolean(data.modifyResult),
        hookPoint: (['intercept', 'after', 'before'].includes(String(data.hookPoint)) ? String(data.hookPoint) : form.hookPoint) as HookPoint,
        logStaticFields: data.logStaticFields === undefined ? form.logStaticFields : Boolean(data.logStaticFields),
        logSampleVars: data.logSampleVars === undefined ? form.logSampleVars : Boolean(data.logSampleVars),
        hookContextFirst: data.hookContextFirst === undefined ? form.hookContextFirst : Boolean(data.hookContextFirst),
      }
      setForm(next)
      setQuickJson(formToJson(next))
      setJsonError('')
    } catch (e: any) {
      setJsonError(e?.message || String(e))
    }
  }

  return (
    <div className="generator-mask" onMouseDown={(ev) => { if (ev.target === ev.currentTarget) onClose() }}>
      <section className="generator-dialog">
        <header>
          <div>
            <h2>代码生成器</h2>
            <p>快速生成 Hook 模板，生成后会打开为临时文件；首次 Ctrl+S 会要求输入保存文件名。</p>
          </div>
          <button title="关闭" onClick={onClose}>×</button>
        </header>

        <div className="generator-body">
          <div className="generator-form">
            <label>
              <span>目标类名</span>
              <input value={form.className} onChange={(ev) => update('className', ev.target.value)} placeholder="com.example.User" />
            </label>
            <label>
              <span>方法名</span>
              <input value={form.methodName} onChange={(ev) => update('methodName', ev.target.value)} placeholder="isPro" />
            </label>
            <label>
              <span>参数类型</span>
              <input value={form.params} onChange={(ev) => update('params', ev.target.value)} placeholder="android.content.Context,int" />
            </label>
            <label>
              <span>返回值的类名</span>
              <input value={form.returnClassName} onChange={(ev) => update('returnClassName', ev.target.value)} placeholder="boolean / java.lang.String" />
            </label>
            <label className="generator-check">
              <input type="checkbox" checked={form.modifyResult} onChange={(ev) => update('modifyResult', ev.target.checked)} />
              <span>启用修改值</span>
            </label>
            <label>
              <span>修改值</span>
              <input disabled={!form.modifyResult} value={form.resultValues} onChange={(ev) => update('resultValues', ev.target.value)} placeholder="true / 123 / &quot;text&quot;" />
            </label>
            <label>
              <span>Hook 方式</span>
              <select value={form.hookPoint} onChange={(ev) => update('hookPoint', ev.target.value as HookPoint)}>
                <option value="intercept">拦截执行</option>
                <option value="after">只记录返回值</option>
                <option value="before">记录参数值</option>
              </select>
            </label>
            <label className="generator-check">
              <input type="checkbox" checked={form.logStaticFields} onChange={(ev) => update('logStaticFields', ev.target.checked)} />
              <span>记录静态变量</span>
            </label>
            <label className="generator-check">
              <input type="checkbox" checked={form.logSampleVars} onChange={(ev) => update('logSampleVars', ev.target.checked)} />
              <span>记录示例变量 this/args</span>
            </label>
            <label className="generator-check wide">
              <input type="checkbox" checked={form.hookContextFirst} onChange={(ev) => update('hookContextFirst', ev.target.checked)} />
              <span>先 Hook android.content.Context（部分加壳软件需要）</span>
            </label>
          </div>

          <div className="generator-json">
            <div className="generator-section-title">快速 JSON 导入 / 导出</div>
            <textarea value={quickJson} onChange={(ev) => setQuickJson(ev.target.value)} spellCheck={false} />
            {jsonError ? <div className="generator-error">JSON 错误：{jsonError}</div> : null}
            <div className="generator-actions small-actions">
              <button onClick={importJson}>导入 JSON</button>
              <button onClick={() => { setQuickJson(formToJson(form)); setJsonError('') }}>从表单导出</button>
            </div>
            <div className="generator-section-title preview-title">生成文件名</div>
            <code>{generated.suggestedPath}</code>
          </div>
        </div>

        <footer>
          <button onClick={onClose}>取消</button>
          <button className="primary" onClick={() => onGenerate(generated)}>生成到临时文件</button>
        </footer>
      </section>
    </div>
  )
}
