import { useCallback, useEffect, useMemo, useState, type MouseEvent as ReactMouseEvent } from "react";
import { api, post } from "./api/client";
import type {
  ApiStatus,
  AppInfo,
  HookSettings,
  ReadScriptResponse,
  ScriptInfo,
  ScriptMeta,
} from "./types/webide";
import { TopBar } from "./components/TopBar";
import { AppListPanel } from "./components/AppListPanel";
import { HookSettingsPanel } from "./components/HookSettingsPanel";
import { BottomConsole, type ConsoleLine } from "./components/BottomConsole";
import { ScriptManagerPanel } from "./components/ScriptManagerPanel";
import { ScriptInfoPanel } from "./components/ScriptInfoPanel";
import { MonacoCodeEditor } from "./editor/MonacoCodeEditor";

type PageMode = "apps" | "scripts";

interface OpenTab {
  path: string;
  content: string;
  metadata: ScriptMeta | null;
  dirty: boolean;
}

let nextLogId = 1;

export function App() {
  const [status, setStatus] = useState<ApiStatus | null>(null);
  const [apps, setApps] = useState<AppInfo[]>([]);
  const [scripts, setScripts] = useState<ScriptInfo[]>([]);
  const [allScripts, setAllScripts] = useState<ScriptInfo[]>([]);
  const [selectedApp, setSelectedApp] = useState<AppInfo | null>(null);
  const [hookSettings, setHookSettings] = useState<HookSettings | null>(null);
  const [appQuery, setAppQuery] = useState("");
  const [showSystem, setShowSystem] = useState(false);
  const [pageMode, setPageMode] = useState<PageMode>("apps");
  const [openTabs, setOpenTabs] = useState<OpenTab[]>([]);
  const [currentPath, setCurrentPath] = useState<string | null>(null);
  const [logs, setLogs] = useState<ConsoleLine[]>([]);
  const [consoleHeight, setConsoleHeight] = useState(() => {
    const saved = Number(window.localStorage.getItem("xhh.consoleHeight"));
    return Number.isFinite(saved) && saved > 0 ? saved : 180;
  });
  const [terminalVisible, setTerminalVisible] = useState(true);
  const [leftPanelVisible, setLeftPanelVisible] = useState(true);
  const [rightPanelVisible, setRightPanelVisible] = useState(true);
  const [leftPanelWidth, setLeftPanelWidth] = useState(() => {
    const saved = Number(window.localStorage.getItem("xhh.leftPanelWidth"));
    return Number.isFinite(saved) && saved > 0 ? saved : 285;
  });
  const [rightPanelWidth, setRightPanelWidth] = useState(() => {
    const saved = Number(window.localStorage.getItem("xhh.rightPanelWidth"));
    return Number.isFinite(saved) && saved > 0 ? saved : 320;
  });

  const selectedPackage = selectedApp?.packageName;
  const activeTab = useMemo(
    () => openTabs.find((tab) => tab.path === currentPath) || null,
    [openTabs, currentPath],
  );
  const content = activeTab?.content || "";
  const metadata = activeTab?.metadata || null;
  const dirty = !!activeTab?.dirty;
  const hasAnyDirty = openTabs.some((tab) => tab.dirty);

  const statusText = useMemo(() => {
    if (!status) return "未连接";
    return `${status.server || "WebIDE"} | ${status.process || ""} | ${status.scriptDir || ""}`;
  }, [status]);

  const log = useCallback((text: string, type?: ConsoleLine["type"]) => {
    const now = new Date().toLocaleTimeString();
    setLogs((old) => {
      const next = [
        ...old,
        { id: nextLogId++, text: `[${now}] ${text}`, type },
      ];
      return next.length > 2000 ? next.slice(next.length - 2000) : next;
    });
  }, []);

  const run = useCallback(
    async (fn: () => Promise<void> | void) => {
      try {
        await fn();
      } catch (e: any) {
        log(e?.message || String(e), "err");
      }
    },
    [log],
  );

  const loadStatus = useCallback(async () => {
    const data = await api<ApiStatus>("/api/status");
    setStatus(data);
  }, []);

  const loadApps = useCallback(async () => {
    const data = await api<{ ok: boolean; apps: AppInfo[]; count?: number }>(
      `/api/apps?query=${encodeURIComponent(appQuery)}&showSystem=${showSystem ? "true" : "false"}`,
    );
    setApps(data.apps || []);
  }, [appQuery, showSystem]);

  const loadScripts = useCallback(async (packageName?: string) => {
    const suffix = packageName
      ? `?packageName=${encodeURIComponent(packageName)}`
      : "";
    const data = await api<{
      ok: boolean;
      scripts: ScriptInfo[];
      count?: number;
    }>("/api/scripts" + suffix);
    setScripts(data.scripts || []);
  }, []);

  const loadAllScripts = useCallback(async () => {
    const data = await api<{
      ok: boolean;
      scripts: ScriptInfo[];
      count?: number;
    }>("/api/scripts");
    setAllScripts(data.scripts || []);
  }, []);

  const loadHookSettings = useCallback(
    async (packageName: string) => {
      const data = await api<HookSettings>(
        `/api/hook-settings?packageName=${encodeURIComponent(packageName)}`,
      );
      setHookSettings(data);
      log(`已加载 Hook 设置：${packageName}`);
    },
    [log],
  );

  const refreshScriptLists = useCallback(async () => {
    await loadAllScripts();
    if (selectedPackage) await loadScripts(selectedPackage);
  }, [loadAllScripts, loadScripts, selectedPackage]);

  const setTabContent = useCallback(
    (value: string) => {
      if (!currentPath) return;
      setOpenTabs((old) =>
        old.map((tab) =>
          tab.path === currentPath
            ? { ...tab, content: value, dirty: true }
            : tab,
        ),
      );
    },
    [currentPath],
  );

  const openScript = useCallback(
    async (path: string) => {
      if (!path) return;
      const exists = openTabs.some((tab) => tab.path === path);
      if (exists) {
        setCurrentPath(path);
        return;
      }
      const data = await api<ReadScriptResponse>(
        `/api/scripts/read?path=${encodeURIComponent(path)}`,
      );
      setOpenTabs((old) => [
        ...old,
        {
          path: data.path,
          content: data.content || "",
          metadata: data.metadata || null,
          dirty: false,
        },
      ]);
      setCurrentPath(data.path);
      log(`打开脚本：${data.path}`);
    },
    [openTabs, log],
  );

  const closeTab = useCallback(
    (path: string) => {
      const tab = openTabs.find((item) => item.path === path);
      if (tab?.dirty && !window.confirm(`脚本 ${path} 尚未保存，确定关闭吗？`))
        return;
      setOpenTabs((old) => {
        const idx = old.findIndex((item) => item.path === path);
        const next = old.filter((item) => item.path !== path);
        if (currentPath === path) {
          const fallback = next[Math.max(0, Math.min(idx, next.length - 1))];
          setCurrentPath(fallback?.path || null);
        }
        return next;
      });
    },
    [openTabs, currentPath],
  );

  const saveTab = useCallback(
    async (tab: OpenTab) => {
      const data = await post<{
        ok: boolean;
        path: string;
        size: number;
        metadata?: ScriptMeta;
      }>("/api/scripts/save", {
        path: tab.path,
        content: tab.content,
      });
      setOpenTabs((old) =>
        old.map((item) =>
          item.path === tab.path
            ? {
                ...item,
                path: data.path,
                metadata: data.metadata || null,
                dirty: false,
              }
            : item,
        ),
      );
      if (currentPath === tab.path) setCurrentPath(data.path);
      log(`已保存：${data.path} (${data.size} bytes)`, "ok");
      await refreshScriptLists();
      if (selectedPackage) await loadHookSettings(selectedPackage);
    },
    [currentPath, refreshScriptLists, selectedPackage, loadHookSettings, log],
  );

  const saveScript = useCallback(async () => {
    if (!activeTab) {
      log("没有打开脚本，无法保存", "warn");
      return;
    }
    await saveTab(activeTab);
  }, [activeTab, saveTab, log]);

  const createScript = useCallback(async () => {
    const defaultName = selectedPackage
      ? `${selectedPackage.replace(/[^A-Za-z0-9_.-]/g, "_")}.js`
      : "new_script.js";
    const path = window.prompt("新脚本文件名：", defaultName);
    if (!path) return;
    const data = await post<{ ok: boolean; path: string }>(
      "/api/scripts/create",
      {
        path,
        target: selectedPackage || "*",
      },
    );
    log(`已创建：${data.path}`, "ok");
    await refreshScriptLists();
    if (selectedPackage) await loadHookSettings(selectedPackage);
    await openScript(data.path);
  }, [selectedPackage, openScript, refreshScriptLists, loadHookSettings, log]);

  const renameScript = useCallback(async () => {
    if (!activeTab) {
      log("没有打开脚本", "warn");
      return;
    }
    const to = window.prompt("重命名为：", activeTab.path);
    if (!to || to === activeTab.path) return;
    const data = await post<{ ok: boolean; path: string }>(
      "/api/scripts/rename",
      { from: activeTab.path, to },
    );
    setOpenTabs((old) =>
      old.map((tab) =>
        tab.path === activeTab.path
          ? { ...tab, path: data.path, dirty: false }
          : tab,
      ),
    );
    setCurrentPath(data.path);
    log(`已重命名：${data.path}`, "ok");
    await refreshScriptLists();
    if (selectedPackage) await loadHookSettings(selectedPackage);
  }, [activeTab, selectedPackage, refreshScriptLists, loadHookSettings, log]);

  const deleteScript = useCallback(async () => {
    if (!activeTab) {
      log("没有打开脚本", "warn");
      return;
    }
    if (!window.confirm(`确定删除 ${activeTab.path} 吗？`)) return;
    await post<{ ok: boolean }>("/api/scripts/delete", {
      path: activeTab.path,
    });
    log(`已删除：${activeTab.path}`, "ok");
    setOpenTabs((old) => old.filter((tab) => tab.path !== activeTab.path));
    setCurrentPath((oldPath) => {
      if (oldPath !== activeTab.path) return oldPath;
      const fallback = openTabs.find((tab) => tab.path !== activeTab.path);
      return fallback?.path || null;
    });
    await refreshScriptLists();
    if (selectedPackage) await loadHookSettings(selectedPackage);
  }, [
    activeTab,
    openTabs,
    selectedPackage,
    refreshScriptLists,
    loadHookSettings,
    log,
  ]);

  const selectApp = useCallback(
    async (app: AppInfo) => {
      setSelectedApp(app);
      await loadHookSettings(app.packageName);
      await loadScripts(app.packageName);
    },
    [loadHookSettings, loadScripts],
  );

  const setAppEnabled = useCallback(
    async (enabled: boolean) => {
      if (!selectedPackage) return;
      const data = await post<{ ok: boolean; enabled: boolean }>(
        "/api/hook-settings/app-enabled",
        {
          packageName: selectedPackage,
          enabled,
        },
      );
      log(`${enabled ? "启用" : "禁用"}应用：${selectedPackage}`, "ok");
      setSelectedApp((old) => (old ? { ...old, enabled: data.enabled } : old));
      await loadApps();
      await loadHookSettings(selectedPackage);
    },
    [selectedPackage, loadApps, loadHookSettings, log],
  );

  const setScriptEnabled = useCallback(
    async (scriptId: string, enabled: boolean) => {
      if (!selectedPackage) return;
      await post("/api/hook-settings/script-enabled", {
        packageName: selectedPackage,
        scriptId,
        enabled,
      });
      log(`${enabled ? "启用" : "禁用"}脚本：${scriptId}`, "ok");
      await loadHookSettings(selectedPackage);
    },
    [selectedPackage, loadHookSettings, log],
  );

  const syncCurrentApp = useCallback(async () => {
    if (!selectedPackage) {
      log("请选择应用", "warn");
      return;
    }
    if (activeTab?.dirty) await saveTab(activeTab);
    const data = await post<{
      ok: boolean;
      count: number;
      scripts?: string[];
      extra?: Record<string, unknown>;
    }>("/api/hook-settings/sync", {
      packageName: selectedPackage,
      restart: false,
      launch: false,
    });
    log(
      `同步完成：${data.count} 个脚本 ${JSON.stringify(data.scripts || [])}`,
      "ok",
    );
  }, [selectedPackage, activeTab, saveTab, log]);

  const restartCurrentApp = useCallback(async () => {
    if (!selectedPackage) {
      log("请选择应用", "warn");
      return;
    }
    const data = await post<{ ok: boolean; packageName: string }>(
      "/api/apps/restart",
      {
        packageName: selectedPackage,
        launch: true,
      },
    );
    log(`已重启应用：${data.packageName}`, "ok");
  }, [selectedPackage, log]);

  const syncAndRestartCurrentApp = useCallback(async () => {
    if (!selectedPackage) {
      log("请选择应用", "warn");
      return;
    }
    if (activeTab?.dirty) await saveTab(activeTab);
    const data = await post<{
      ok: boolean;
      count: number;
      scripts?: string[];
      extra?: Record<string, unknown>;
    }>("/api/hook-settings/sync", {
      packageName: selectedPackage,
      restart: true,
      launch: true,
    });
    log(
      `同步并重启完成：${data.count} 个脚本 ${JSON.stringify(data.scripts || [])}`,
      "ok",
    );
  }, [selectedPackage, activeTab, saveTab, log]);

  const openTerminalPage = useCallback(() => {
    const pkg = selectedPackage?.trim() || "";
    const url = pkg
      ? `/terminal?packageName=${encodeURIComponent(pkg)}`
      : "/terminal";
    window.open(url, "xhh-terminal");
  }, [selectedPackage]);

  const toggleTerminal = useCallback(() => {
    setTerminalVisible((old) => !old);
  }, []);

  const startLeftResize = useCallback((ev: ReactMouseEvent<HTMLDivElement>) => {
    ev.preventDefault();
    const startX = ev.clientX;
    const startWidth = leftPanelWidth;
    document.body.classList.add("side-resizing");

    const onMove = (moveEv: MouseEvent) => {
      const next = Math.max(180, Math.min(620, startWidth + moveEv.clientX - startX));
      setLeftPanelWidth(next);
    };

    const onUp = () => {
      document.body.classList.remove("side-resizing");
      document.removeEventListener("mousemove", onMove);
      document.removeEventListener("mouseup", onUp);
    };

    document.addEventListener("mousemove", onMove);
    document.addEventListener("mouseup", onUp);
  }, [leftPanelWidth]);

  const startRightResize = useCallback((ev: ReactMouseEvent<HTMLDivElement>) => {
    ev.preventDefault();
    const startX = ev.clientX;
    const startWidth = rightPanelWidth;
    document.body.classList.add("side-resizing");

    const onMove = (moveEv: MouseEvent) => {
      const next = Math.max(220, Math.min(720, startWidth + startX - moveEv.clientX));
      setRightPanelWidth(next);
    };

    const onUp = () => {
      document.body.classList.remove("side-resizing");
      document.removeEventListener("mousemove", onMove);
      document.removeEventListener("mouseup", onUp);
    };

    document.addEventListener("mousemove", onMove);
    document.addEventListener("mouseup", onUp);
  }, [rightPanelWidth]);

  useEffect(() => {
    window.localStorage.setItem("xhh.leftPanelWidth", String(leftPanelWidth));
  }, [leftPanelWidth]);

  useEffect(() => {
    window.localStorage.setItem("xhh.rightPanelWidth", String(rightPanelWidth));
  }, [rightPanelWidth]);

  useEffect(() => {
    window.localStorage.setItem("xhh.consoleHeight", String(consoleHeight));
  }, [consoleHeight]);

  useEffect(() => {
    run(async () => {
      await loadStatus();
      await loadApps();
      await loadAllScripts();
      await loadScripts();
    });
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(() => run(loadApps), 250);
    return () => window.clearTimeout(timer);
  }, [appQuery, showSystem]);

  useEffect(() => {
    if (pageMode === "scripts") {
      run(loadAllScripts);
    } else if (selectedPackage) {
      run(() => loadScripts(selectedPackage));
    }
  }, [pageMode, selectedPackage]);

  useEffect(() => {
    const onKeyDown = (ev: KeyboardEvent) => {
      const ctrl = ev.ctrlKey || ev.metaKey;
      const key = ev.key.toLowerCase();

      if (ev.key === "F5") {
        ev.preventDefault();
        run(syncAndRestartCurrentApp);
        return;
      }

      if (ctrl && ev.shiftKey && key === "s") {
        ev.preventDefault();
        run(syncCurrentApp);
        return;
      }

      if (ctrl && key === "s") {
        ev.preventDefault();
        run(saveScript);
        return;
      }

      if (ev.altKey && key === "r") {
        ev.preventDefault();
        run(restartCurrentApp);
        return;
      }

      if (ctrl && ev.altKey && key === "t") {
        ev.preventDefault();
        toggleTerminal();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [
    saveScript,
    syncCurrentApp,
    restartCurrentApp,
    syncAndRestartCurrentApp,
    toggleTerminal,
    run,
  ]);

  useEffect(() => {
    if (!selectedPackage) return;
    const source = new EventSource(
      `/api/logs/stream?packageName=${encodeURIComponent(selectedPackage)}`,
    );
    let reportedError = false;

    source.addEventListener("open", () => {
      reportedError = false;
      log(`实时日志已连接：${selectedPackage}`, "ok");
    });

    source.addEventListener("log", (event) => {
      try {
        const data = JSON.parse((event as MessageEvent).data) as {
          line?: string;
        };
        if (data.line) log(data.line, "target");
      } catch {
        const text = (event as MessageEvent).data;
        if (text) log(text, "target");
      }
    });

    source.addEventListener("error", () => {
      if (!reportedError) {
        reportedError = true;
        log(`实时日志连接中断，浏览器会自动重连：${selectedPackage}`, "warn");
      }
    });

    return () => source.close();
  }, [selectedPackage, log]);

  const tabTitle = activeTab ? activeTab.path : "未打开脚本";
  const leftToolTitle = pageMode === "apps" ? "软件列表" : "全部脚本";
  const rightToolTitle = pageMode === "apps" ? "Hook 设置" : "脚本信息";
  const gridTemplateColumns = [
    leftPanelVisible ? `${leftPanelWidth}px` : null,
    "minmax(0, 1fr)",
    rightPanelVisible ? `${rightPanelWidth}px` : null,
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <>
      <TopBar
        statusText={statusText}
        hasDirty={hasAnyDirty}
        pageMode={pageMode}
        onPageModeChange={setPageMode}
        onSave={() => run(saveScript)}
        onNew={() => run(createScript)}
        onRename={() => run(renameScript)}
        onDelete={() => run(deleteScript)}
        onSync={() => run(syncCurrentApp)}
        onRestart={() => run(restartCurrentApp)}
        onSyncRestart={() => run(syncAndRestartCurrentApp)}
        terminalVisible={terminalVisible}
        leftPanelVisible={leftPanelVisible}
        rightPanelVisible={rightPanelVisible}
        leftToolTitle={leftToolTitle}
        rightToolTitle={rightToolTitle}
        onToggleTerminal={toggleTerminal}
        onToggleLeftPanel={() => setLeftPanelVisible((old) => !old)}
        onToggleRightPanel={() => setRightPanelVisible((old) => !old)}
      />
      <main
        className={
          "main" +
          (!leftPanelVisible ? " left-collapsed" : "") +
          (!rightPanelVisible ? " right-collapsed" : "")
        }
        style={{ gridTemplateColumns }}
      >
        {leftPanelVisible ? (
          <div className="panel-wrap left-wrap">
            {pageMode === "apps" ? (
              <AppListPanel
                apps={apps}
                selectedPackage={selectedPackage}
                query={appQuery}
                showSystem={showSystem}
                onQueryChange={setAppQuery}
                onShowSystemChange={setShowSystem}
                onReload={() => run(loadApps)}
                onSelect={(app) => run(() => selectApp(app))}
              />
            ) : (
              <ScriptManagerPanel
                scripts={allScripts}
                currentPath={currentPath}
                onOpenScript={(path) => run(() => openScript(path))}
                onNewScript={() => run(createScript)}
                onReload={() => run(loadAllScripts)}
              />
            )}
            <div
              className="side-resizer left"
              title="拖动调整左侧区域宽度"
              onMouseDown={startLeftResize}
            />
            <button
              className="tool-collapse left"
              title={`隐藏${leftToolTitle}`}
              onClick={() => setLeftPanelVisible(false)}
            >
              ‹
            </button>
          </div>
        ) : null}

        <section className="center">
          <div className="tabs">
            {openTabs.length === 0 ? (
              <div className="tab active" title="未打开脚本">
                未打开脚本
              </div>
            ) : (
              openTabs.map((tab) => (
                <div
                  key={tab.path}
                  className={
                    "tab" + (tab.path === currentPath ? " active" : "")
                  }
                  title={tab.path}
                  onClick={() => setCurrentPath(tab.path)}
                >
                  <span>
                    {tab.dirty ? "● " : ""}
                    {tab.path}
                  </span>
                  <button
                    className="tab-close"
                    title="关闭标签页"
                    onClick={(ev) => {
                      ev.stopPropagation();
                      closeTab(tab.path);
                    }}
                  >
                    ×
                  </button>
                </div>
              ))
            )}
          </div>
          <div className="editor-wrap">
            <MonacoCodeEditor
              key={currentPath || "empty-editor"}
              value={content}
              onChange={setTabContent}
              onSave={() => run(saveScript)}
            />
          </div>
        </section>

        {rightPanelVisible ? (
          <div className="panel-wrap right-wrap">
            <div
              className="side-resizer right"
              title="拖动调整右侧区域宽度"
              onMouseDown={startRightResize}
            />
            {pageMode === "apps" ? (
              <HookSettingsPanel
                settings={hookSettings}
                metadata={metadata}
                onAppEnabledChange={(enabled) =>
                  run(() => setAppEnabled(enabled))
                }
                onScriptEnabledChange={(scriptId, enabled) =>
                  run(() => setScriptEnabled(scriptId, enabled))
                }
                onOpenScript={(path) => run(() => openScript(path))}
              />
            ) : (
              <ScriptInfoPanel
                path={tabTitle}
                metadata={metadata}
                dirty={dirty}
              />
            )}
            <button
              className="tool-collapse right"
              title={`隐藏${rightToolTitle}`}
              onClick={() => setRightPanelVisible(false)}
            >
              ›
            </button>
          </div>
        ) : null}
      </main>
      {terminalVisible ? (
        <BottomConsole
          lines={logs}
          livePackage={selectedPackage}
          height={consoleHeight}
          onHeightChange={setConsoleHeight}
          onClear={() => setLogs([])}
          onOpenStandalone={openTerminalPage}
          onHide={() => setTerminalVisible(false)}
        />
      ) : null}
    </>
  );
}
