import type { KeyboardEvent, MouseEvent } from 'react'

export type DialogButtonRole = 'primary' | 'danger' | 'normal' | 'cancel'

export interface DialogButton<T = string> {
  label: string
  value: T
  role?: DialogButtonRole
}

export type UiDialogRequest =
  | {
      kind: 'alert'
      title: string
      message?: string
      resolve: () => void
    }
  | {
      kind: 'confirm'
      title: string
      message?: string
      confirmText?: string
      cancelText?: string
      danger?: boolean
      resolve: (value: boolean) => void
    }
  | {
      kind: 'prompt'
      title: string
      message?: string
      defaultValue?: string
      placeholder?: string
      multiline?: boolean
      confirmText?: string
      cancelText?: string
      resolve: (value: string | null) => void
    }
  | {
      kind: 'choice'
      title: string
      message?: string
      buttons: DialogButton[]
      resolve: (value: string | null) => void
    }

interface Props {
  request: UiDialogRequest | null
  value: string
  onValueChange: (value: string) => void
  onClose: () => void
}

export function UiDialog({ request, value, onValueChange, onClose }: Props) {
  if (!request) return null

  const closeAlert = () => {
    if (request.kind === 'alert') request.resolve()
    onClose()
  }

  const closeConfirm = (ok: boolean) => {
    if (request.kind === 'confirm') request.resolve(ok)
    onClose()
  }

  const closePrompt = (result: string | null) => {
    if (request.kind === 'prompt') request.resolve(result)
    onClose()
  }

  const closeChoice = (result: string | null) => {
    if (request.kind === 'choice') request.resolve(result)
    onClose()
  }

  const onMaskMouseDown = (ev: MouseEvent<HTMLDivElement>) => {
    if (ev.target !== ev.currentTarget) return
    if (request.kind === 'alert') closeAlert()
    else if (request.kind === 'confirm') closeConfirm(false)
    else if (request.kind === 'prompt') closePrompt(null)
    else closeChoice(null)
  }

  const onKeyDown = (ev: KeyboardEvent) => {
    if (ev.key === 'Escape') {
      ev.preventDefault()
      if (request.kind === 'alert') closeAlert()
      else if (request.kind === 'confirm') closeConfirm(false)
      else if (request.kind === 'prompt') closePrompt(null)
      else closeChoice(null)
    }
    if (ev.key === 'Enter' && request.kind === 'prompt' && !request.multiline) {
      ev.preventDefault()
      closePrompt(value)
    }
  }

  return (
    <div className="ui-dialog-mask" onMouseDown={onMaskMouseDown} onKeyDown={onKeyDown}>
      <section className="ui-dialog" role="dialog" aria-modal="true">
        <header className="ui-dialog-header">
          <h2>{request.title}</h2>
          <button title="关闭" onClick={() => {
            if (request.kind === 'alert') closeAlert()
            else if (request.kind === 'confirm') closeConfirm(false)
            else if (request.kind === 'prompt') closePrompt(null)
            else closeChoice(null)
          }}>×</button>
        </header>
        {request.message ? <div className="ui-dialog-message">{request.message}</div> : null}
        {request.kind === 'prompt' ? (
          request.multiline ? (
            <textarea
              autoFocus
              className="ui-dialog-input multiline"
              value={value}
              placeholder={request.placeholder}
              onChange={(ev) => onValueChange(ev.target.value)}
            />
          ) : (
            <input
              autoFocus
              className="ui-dialog-input"
              type="text"
              value={value}
              placeholder={request.placeholder}
              onChange={(ev) => onValueChange(ev.target.value)}
            />
          )
        ) : null}
        <footer className="ui-dialog-actions">
          {request.kind === 'alert' ? (
            <button className="primary" onClick={closeAlert}>确定</button>
          ) : request.kind === 'confirm' ? (
            <>
              <button onClick={() => closeConfirm(false)}>{request.cancelText || '取消'}</button>
              <button className={request.danger ? 'danger' : 'primary'} onClick={() => closeConfirm(true)}>{request.confirmText || '确定'}</button>
            </>
          ) : request.kind === 'prompt' ? (
            <>
              <button onClick={() => closePrompt(null)}>{request.cancelText || '取消'}</button>
              <button className="primary" onClick={() => closePrompt(value)}>{request.confirmText || '确定'}</button>
            </>
          ) : (
            request.buttons.map((button) => (
              <button
                key={button.value}
                className={button.role === 'primary' ? 'primary' : button.role === 'danger' ? 'danger' : ''}
                onClick={() => closeChoice(button.value)}
              >
                {button.label}
              </button>
            ))
          )}
        </footer>
      </section>
    </div>
  )
}
