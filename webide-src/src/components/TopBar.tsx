import {Icon} from './Icon'

interface Props {
    statusText: string
    hasDirty: boolean
    pageMode: 'apps' | 'scripts'
    onPageModeChange: (mode: 'apps' | 'scripts') => void
    onSave: () => void
    onNew: () => void
    onRename: () => void
    onDelete: () => void
    onOpenGenerator: () => void
    onSync: () => void
    onRestart: () => void
    onSyncRestart: () => void
    onDebugRun: () => void
    onStopDebug: () => void
    debugEnabled: boolean
    terminalVisible: boolean
    leftPanelVisible: boolean
    rightPanelVisible: boolean
    leftToolTitle: string
    rightToolTitle: string
    onToggleTerminal: () => void
    onToggleLeftPanel: () => void
    onToggleRightPanel: () => void
}

export function TopBar(props: Props) {
    return (
        <div className="topbar">
            <span className="brand">XiaoHeiHook WebIDE</span>
            <button
                className={props.pageMode === 'apps' ? 'nav-btn active' : 'nav-btn'}
                onClick={() => props.onPageModeChange('apps')}
                title="软件列表与 Hook 设置"
            >
                <Icon name="apps"/> 软件
            </button>
            <button
                className={props.pageMode === 'scripts' ? 'nav-btn active' : 'nav-btn'}
                onClick={() => props.onPageModeChange('scripts')}
                title="管理全部脚本"
            >
                <Icon name="scripts"/> 全部脚本
            </button>
            <span className="sep"/>
            <button
                className={props.leftPanelVisible ? 'icon-btn active' : 'icon-btn'}
                onClick={props.onToggleLeftPanel}
                title={props.leftPanelVisible ? `隐藏${props.leftToolTitle}` : `显示${props.leftToolTitle}`}
            >
                <Icon name="leftPanel"/>
            </button>
            <button
                className={props.rightPanelVisible ? 'icon-btn active' : 'icon-btn'}
                onClick={props.onToggleRightPanel}
                title={props.rightPanelVisible ? `隐藏${props.rightToolTitle}` : `显示${props.rightToolTitle}`}
            >
                <Icon name="rightPanel"/>
            </button>
            <button
                className={props.terminalVisible ? 'icon-btn active' : 'icon-btn'}
                onClick={props.onToggleTerminal}
                title={props.terminalVisible ? '隐藏下方终端 Ctrl+Alt+T' : '显示下方终端 Ctrl+Alt+T'}
            >
                <Icon name="terminal"/>
            </button>
            <span className="sep"/>
            <button onClick={props.onNew} title="新建脚本"><Icon name="new"/> 新建</button>
            <button onClick={props.onSave} title="保存当前脚本 Ctrl/Cmd+S"><Icon
                name="save"/> {props.hasDirty ? '保存 *' : '保存'}</button>
            <button onClick={props.onRename} title="重命名当前脚本"><Icon name="rename"/> 重命名</button>
            <button onClick={props.onDelete} title="删除当前脚本"><Icon name="delete"/> 删除</button>
            <span className="sep"/>
            <button className="icon-btn" onClick={props.onSync} title="同步当前应用 Ctrl+Shift+S"><Icon name="sync"/>
            </button>
            <button className="icon-btn" onClick={props.onRestart} title="重启应用 Alt+R"><Icon name="restart"/>
            </button>
            <button className="icon-btn run-btn" onClick={props.onSyncRestart} title="运行：普通模式同步并重启应用 F5">
                <Icon name="run"/></button>
            <button className={props.debugEnabled ? 'icon-btn debug-btn active' : 'icon-btn debug-btn'}
                    onClick={props.onDebugRun} title="调试运行：启用 WebIDE 调试模式、同步并重启应用 Ctrl+F5"><Icon
                name="debug"/></button>
            <button className="icon-btn stop-debug-btn" onClick={props.onStopDebug} title="停止调试 Shift+F5"><Icon
                name="stop"/></button>
            <span className="sep"/>
            <button className="generator-group-btn" onClick={props.onOpenGenerator} title="打开 Hook 代码生成器"><Icon name="generator"/> 代码生成器</button>
            <span className="sep"/>
            <span className="status">
              {props.statusText} |{' '}
                    <a
                        href="https://lab.lovepikachu.top/document/xiaoheihook"
                        target="_blank"
                        rel="noopener noreferrer"
                        style={{
                            color: '#6493d8',
                            textDecoration: 'none',
                            cursor: 'pointer',
                        }}
                    >
                帮助文档
              </a>
            </span>
        </div>
    )
}
