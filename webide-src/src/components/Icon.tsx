interface IconProps {
  name: 'save' | 'new' | 'rename' | 'delete' | 'sync' | 'restart' | 'syncRestart' | 'run' | 'terminal' | 'refresh' | 'clear' | 'hide' | 'apps' | 'scripts' | 'leftPanel' | 'rightPanel' | 'debug' | 'stop'
}

const paths: Record<IconProps['name'], string> = {
  save: 'M17 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V7l-4-4ZM7 5h8v5H7V5Zm10 14H7v-6h10v6Z',
  new: 'M11 4h2v7h7v2h-7v7h-2v-7H4v-2h7V4Z',
  rename: 'M5 17.5V19h1.5L17.1 8.4l-1.5-1.5L5 17.5ZM18.3 7.2a1 1 0 0 0 0-1.4l-1.1-1.1a1 1 0 0 0-1.4 0l-.9.9 2.5 2.5.9-.9Z',
  delete: 'M6 7h12l-1 14H7L6 7Zm3-4h6l1 2h4v2H4V5h4l1-2Z',
  sync: 'M7 7h8.5L13 4.5 14.5 3 20 8.5 14.5 14 13 12.5 15.5 10H7V7Zm10 10H8.5L11 19.5 9.5 21 4 15.5 9.5 10 11 11.5 8.5 14H17v3Z',
  restart: 'M12 5a7 7 0 1 1-6.3 4H3l4-4 4 4H8a5 5 0 1 0 4-2V5Z',
  syncRestart: 'M6 7h8l-2-2 1.4-1.4L18 8.2l-4.6 4.6L12 11.4l2.4-2.4H6V7Zm12 10h-8l2 2-1.4 1.4L6 15.8l4.6-4.6L12 12.6 9.6 15H18v2Z',
  run: 'M8 5v14l11-7L8 5Z',
  terminal: 'M3 5h18v14H3V5Zm2 2v10h14V7H5Zm2 2 3 3-3 3-1.4-1.4L7.2 12 5.6 10.4 7 9Zm4 5h5v2h-5v-2Z',
  refresh: 'M17.7 6.3A8 8 0 1 0 20 12h-2a6 6 0 1 1-1.8-4.2L13 11h8V3l-3.3 3.3Z',
  clear: 'M6 19 19 6l-1.4-1.4-13 13L6 19Zm-1-8h5l2-2H5v2Zm0 4h1l2-2H5v2Zm9-8h5V5h-3l-2 2Z',
  hide: 'M2 4.3 3.3 3 21 20.7 19.7 22l-3.1-3.1A11.9 11.9 0 0 1 12 20C7 20 2.7 16.9 1 12c.8-2.2 2.2-4.1 4-5.5L2 4.3Zm5.4 5.3A5 5 0 0 0 14.4 16.6l-1.8-1.8a2.7 2.7 0 0 1-.6.1 3 3 0 0 1-3-3c0-.2 0-.4.1-.6L7.4 9.6ZM12 4c5 0 9.3 3.1 11 8-.5 1.4-1.3 2.8-2.3 3.9l-3.1-3.1A5 5 0 0 0 11.2 6.4L8.9 4.1C9.9 4 10.9 4 12 4Z',
  apps: 'M4 4h7v7H4V4Zm9 0h7v7h-7V4ZM4 13h7v7H4v-7Zm9 0h7v7h-7v-7Z',
  scripts: 'M6 3h9l5 5v13H6V3Zm8 1.5V9h4.5L14 4.5ZM8 13h8v2H8v-2Zm0 4h8v2H8v-2Z',
  leftPanel: 'M3 4h18v16H3V4Zm2 2v12h5V6H5Zm7 0v12h7V6h-7Z',
  rightPanel: 'M3 4h18v16H3V4Zm2 2v12h7V6H5Zm9 0v12h5V6h-5Z',
  debug: 'M9 2h2v3h2V2h2v3.2c1.2.4 2.2 1.2 2.9 2.3L21 6l1 1.7-3 1.7c.1.5.2 1 .2 1.6v1H22v2h-2.8v1c0 .6-.1 1.1-.2 1.6l3 1.7-1 1.7-2.9-1.6A6.9 6.9 0 0 1 12 22a6.9 6.9 0 0 1-6.1-3.6L3 20l-1-1.7 3-1.7a8 8 0 0 1-.2-1.6v-1H2v-2h2.8v-1c0-.6.1-1.1.2-1.6L2 7.7 3 6l2.9 1.6A7.2 7.2 0 0 1 9 5.2V2Zm3 5a5 5 0 0 0-5 5v3a5 5 0 0 0 10 0v-3a5 5 0 0 0-5-5Zm-2 5h4v2h-4v-2Z',
  stop: 'M6 6h12v12H6V6Z'
}

export function Icon({ name }: IconProps) {
  return (
    <svg className="fa-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path d={paths[name]} />
    </svg>
  )
}
