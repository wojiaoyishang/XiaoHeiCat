let webIdeToken = ''

function rememberToken(data: any) {
  if (data && typeof data.webIdeToken === 'string' && data.webIdeToken) {
    webIdeToken = data.webIdeToken
    window.sessionStorage.setItem('xhh.webIdeToken', webIdeToken)
  }
}

function currentToken() {
  return webIdeToken || window.sessionStorage.getItem('xhh.webIdeToken') || ''
}

export async function api<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await fetch(path, { cache: 'no-store', ...(options || {}) })
  const text = await res.text()
  let data: any = {}
  try {
    data = text ? JSON.parse(text) : {}
  } catch {
    throw new Error(`HTTP ${res.status}: ${text.slice(0, 300)}`)
  }
  rememberToken(data)
  if (!res.ok || data.ok === false) {
    throw new Error(data.error || `HTTP ${res.status}`)
  }
  return data as T
}

export function post<T>(path: string, body?: unknown): Promise<T> {
  return api<T>(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-XiaoHeiHook-Token': currentToken() },
    body: JSON.stringify(body || {})
  })
}
