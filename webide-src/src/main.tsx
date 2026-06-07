import React from 'react'
import ReactDOM from 'react-dom/client'
import { App } from './App'
import { TerminalPage } from './TerminalPage'
import { LogsPage } from './LogsPage'
import './styles.css'

const root = ReactDOM.createRoot(document.getElementById('root')!)
const normalizedPath = window.location.pathname.replace(/\/+$/, '') || '/'
root.render(
  <React.StrictMode>
    {normalizedPath === '/terminal' ? <TerminalPage /> : normalizedPath === '/logs' ? <LogsPage /> : <App />}
  </React.StrictMode>
)
