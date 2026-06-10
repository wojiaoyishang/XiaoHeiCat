import type { SettingField, ScriptSettingsResponse } from "../types/webide";

function fieldTitle(field: SettingField) {
  return field.label || field.title || field.key || field.type;
}

function valueKey(field: SettingField) {
  return field.key || "";
}

function cloneJson<T>(value: T): T {
  return JSON.parse(JSON.stringify(value ?? null));
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function defaultValue(field: SettingField): unknown {
  if (field.default !== undefined) return cloneJson(field.default);
  switch (field.type) {
    case "switch":
    case "checkbox":
      return false;
    case "number":
      return field.integer ? 0 : 0;
    case "text":
      return "";
    case "select":
      return field.options?.[0]?.value ?? "";
    case "radio":
      return field.value ?? false;
    case "tags":
    case "list":
      return [];
    case "custom":
      return {};
    default:
      return "";
  }
}

function childDefaults(fields: SettingField[] = []) {
  const obj: Record<string, unknown> = {};
  for (const field of fields) {
    if (field.type === "group") Object.assign(obj, childDefaults(field.items || []));
    else if (field.key) obj[field.key] = defaultValue(field);
  }
  return obj;
}

interface RendererProps {
  field: SettingField;
  values: Record<string, unknown>;
  onChange: (key: string, value: unknown) => void;
  onPrompt: (title: string, defaultValue?: string, message?: string, multiline?: boolean) => Promise<string | null>;
}

function SettingFieldRenderer({ field, values, onChange, onPrompt }: RendererProps) {
  const key = valueKey(field);
  const value = key ? values[key] : undefined;
  const label = fieldTitle(field);

  if (field.type === "heading") {
    return <div className="settings-heading">{label ? <span>{label}</span> : null}</div>;
  }

  if (field.type === "info") {
    return (
      <div className={`settings-info ${field.tone || "info"}`}>
        {(field.title || field.label) ? <b>{field.title || field.label}</b> : null}
        {field.message ? <p>{field.message}</p> : null}
      </div>
    );
  }

  if (field.type === "group") {
    const hasRadio = (field.items || []).some((item) => item.type === "radio");
    return (
      <fieldset className={"settings-group" + (hasRadio ? " radio-group" : "")}>
        {(field.label || field.title) ? <legend>{field.label || field.title}</legend> : null}
        {(field.items || []).map((item, idx) => (
          <SettingFieldRenderer key={`${item.key || item.type}-${idx}`} field={item} values={values} onChange={onChange} onPrompt={onPrompt} />
        ))}
      </fieldset>
    );
  }

  if (!key) return null;

  if (field.type === "switch") {
    return (
      <label className="settings-row switch-row">
        <span>
          <b>{label}</b>
        </span>
        <input type="checkbox" checked={Boolean(value)} onChange={(ev) => onChange(key, ev.target.checked)} />
      </label>
    );
  }

  if (field.type === "checkbox") {
    return (
      <label className="settings-row checkbox-row">
        <input type="checkbox" checked={Boolean(value)} onChange={(ev) => onChange(key, ev.target.checked)} />
        <span>
          <b>{label}</b>
        </span>
      </label>
    );
  }

  if (field.type === "number") {
    const num = typeof value === "number" ? value : Number(value || 0);
    const step = field.step ?? (field.integer ? 1 : 0.1);
    const update = (raw: string) => {
      const parsed = Number(raw);
      if (!Number.isFinite(parsed)) return;
      onChange(key, field.integer ? Math.round(parsed) : parsed);
    };
    return (
      <label className="settings-row block-row">
        <span>
          <b>{label}</b>
        </span>
        <div className="settings-number-line">
          <input
            type="range"
            min={field.min}
            max={field.max}
            step={step}
            value={num}
            onChange={(ev) => update(ev.target.value)}
          />
          <input
            type="number"
            min={field.min}
            max={field.max}
            step={step}
            value={Number.isFinite(num) ? num : 0}
            onChange={(ev) => update(ev.target.value)}
            onWheel={(ev) => (ev.currentTarget as HTMLInputElement).stepUp(ev.deltaY < 0 ? 1 : -1)}
          />
        </div>
      </label>
    );
  }

  if (field.type === "text") {
    const text = value == null ? "" : String(value);
    return (
      <label className="settings-row block-row">
        <span>
          <b>{label}</b>
        </span>
        {field.multiline ? (
          <textarea
            value={text}
            placeholder={field.placeholder}
            maxLength={field.maxLength}
            onChange={(ev) => onChange(key, ev.target.value)}
            onDoubleClick={async () => {
              const next = await onPrompt(label, text, undefined, true);
              if (next !== null) onChange(key, next);
            }}
          />
        ) : (
          <input
            type={field.masked ? "password" : "text"}
            value={text}
            placeholder={field.placeholder}
            maxLength={field.maxLength}
            onChange={(ev) => onChange(key, ev.target.value)}
          />
        )}
      </label>
    );
  }

  if (field.type === "radio") {
    return (
      <label className="settings-row radio-row">
        <input
          type="radio"
          name={field.key}
          checked={JSON.stringify(value) === JSON.stringify(field.value)}
          onChange={() => onChange(key, field.value ?? true)}
        />
        <span>{label}</span>
      </label>
    );
  }

  if (field.type === "select") {
    return (
      <label className="settings-row block-row">
        <span>
          <b>{label}</b>
        </span>
        <select value={String(value ?? "")} onChange={(ev) => onChange(key, ev.target.value)}>
          {(field.options || []).map((option, idx) => (
            <option key={idx} value={String(option.value)}>{option.label}</option>
          ))}
        </select>
      </label>
    );
  }

  if (field.type === "tags") {
    const tags = asArray(value).map(String);
    return (
      <div className="settings-row block-row tags-row">
        <span>
          <b>{label}</b>
        </span>
        <div className="settings-tags">
          {tags.map((tag, idx) => (
            <span className="tag-pill" key={`${tag}-${idx}`}>
              {tag}
              <button onClick={() => onChange(key, tags.filter((_, i) => i !== idx))}>×</button>
            </span>
          ))}
          <button
            onClick={async () => {
              const next = await onPrompt("添加标签", "");
              if (next && next.trim()) onChange(key, [...tags, next.trim()]);
            }}
          >+ 添加</button>
        </div>
      </div>
    );
  }

  if (field.type === "custom") {
    const obj = asRecord(value);
    const entries = Object.entries(obj).map(([k, v]) => [k, String(v ?? "")] as const);
    return (
      <div className="settings-row block-row custom-row">
        <span>
          <b>{label}</b>
        </span>
        <div className="custom-list">
          {entries.map(([k, v]) => (
            <div className="custom-item" key={k}>
              <input value={k} onChange={(ev) => {
                const nextKey = ev.target.value.trim();
                const next = { ...obj };
                delete next[k];
                if (nextKey) next[nextKey] = v;
                onChange(key, next);
              }} />
              <input value={v} onChange={(ev) => onChange(key, { ...obj, [k]: ev.target.value })} />
              <button onClick={() => { const next = { ...obj }; delete next[k]; onChange(key, next); }}>删除</button>
            </div>
          ))}
          <button onClick={async () => {
            const k = await onPrompt("键名", "key");
            if (k && k.trim()) onChange(key, { ...obj, [k.trim()]: "" });
          }}>+ 添加键值</button>
        </div>
      </div>
    );
  }

  if (field.type === "list") {
    const arr = asArray(value).map(asRecord);
    const items = field.items || [];
    const setItem = (idx: number, itemKey: string, itemValue: unknown) => {
      const next = arr.map((item, i) => i === idx ? { ...item, [itemKey]: itemValue } : item);
      onChange(key, next);
    };
    return (
      <div className="settings-row block-row list-row">
        <span>
          <b>{label}</b>
        </span>
        <div className="settings-list">
          {arr.map((item, idx) => (
            <details key={idx} open className="settings-list-item">
              <summary>
                <span>{label} #{idx + 1}</span>
                <button onClick={(ev) => { ev.preventDefault(); onChange(key, arr.filter((_, i) => i !== idx)); }}>删除</button>
                <button onClick={(ev) => { ev.preventDefault(); onChange(key, [...arr.slice(0, idx + 1), cloneJson(item), ...arr.slice(idx + 1)]); }}>复制</button>
                <button disabled={idx === 0} onClick={(ev) => { ev.preventDefault(); const next = [...arr]; [next[idx - 1], next[idx]] = [next[idx], next[idx - 1]]; onChange(key, next); }}>↑</button>
                <button disabled={idx === arr.length - 1} onClick={(ev) => { ev.preventDefault(); const next = [...arr]; [next[idx + 1], next[idx]] = [next[idx], next[idx + 1]]; onChange(key, next); }}>↓</button>
              </summary>
              <div className="settings-list-fields">
                {items.map((child, childIdx) => (
                  <SettingFieldRenderer
                    key={`${child.key || child.type}-${childIdx}`}
                    field={child}
                    values={item}
                    onChange={(itemKey, itemValue) => setItem(idx, itemKey, itemValue)}
                    onPrompt={onPrompt}
                  />
                ))}
              </div>
            </details>
          ))}
          <button onClick={() => onChange(key, [...arr, childDefaults(items)])}>+ 添加列表项</button>
        </div>
      </div>
    );
  }

  return null;
}

interface Props {
  data: ScriptSettingsResponse;
  values: Record<string, unknown>;
  saving?: boolean;
  onChange: (values: Record<string, unknown>) => void;
  onSave: () => void;
  onReset: () => void;
  onClose: () => void;
  onPrompt: (title: string, defaultValue?: string, message?: string, multiline?: boolean) => Promise<string | null>;
}

export function ScriptSettingsPanel({ data, values, saving, onChange, onSave, onReset, onClose, onPrompt }: Props) {
  const update = (key: string, value: unknown) => onChange({ ...values, [key]: value });
  return (
    <div className="settings-mask" onMouseDown={(ev) => { if (ev.target === ev.currentTarget) onClose(); }}>
      <section className="settings-dialog">
        <header>
          <div>
            <h2>{data.schema.title || "脚本设置"}</h2>
            <p>{data.packageName} · {data.scriptPath}</p>
          </div>
          <button title="关闭" onClick={onClose}>×</button>
        </header>
        <div className="settings-body">
          {(data.schema.fields || []).map((field, idx) => (
            <SettingFieldRenderer key={`${field.key || field.type}-${idx}`} field={field} values={values} onChange={update} onPrompt={onPrompt} />
          ))}
        </div>
        <footer>
          <button onClick={onReset} disabled={saving}>恢复默认</button>
          <button onClick={onSave} disabled={saving}>{saving ? "保存中..." : "保存"}</button>
        </footer>
      </section>
    </div>
  );
}
