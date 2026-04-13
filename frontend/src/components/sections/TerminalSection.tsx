import { useEffect, useRef, useState } from 'react';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import type { IDisposable } from '@xterm/xterm';
import type { VmResponse } from '../../types/api';
import '@xterm/xterm/css/xterm.css';

const WS_BASE_URL = import.meta.env.VITE_WS_BASE_URL ?? 'ws://localhost:8080';

interface Props {
  selectedVm?: VmResponse;
  token: string;
}

export function TerminalSection({ selectedVm, token }: Props): JSX.Element {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const terminalRef = useRef<Terminal | null>(null);
  const fitRef = useRef<FitAddon | null>(null);
  const socketRef = useRef<WebSocket | null>(null);
  const dataListenerRef = useRef<IDisposable | null>(null);
  const [connected, setConnected] = useState(false);

  const disposeSocket = (): void => {
    if (socketRef.current) {
      socketRef.current.close();
      socketRef.current = null;
    }
    if (dataListenerRef.current) {
      dataListenerRef.current.dispose();
      dataListenerRef.current = null;
    }
    setConnected(false);
  };

  useEffect(() => {
    const term = new Terminal({
      cursorBlink: true,
      fontFamily: 'IBM Plex Mono, monospace',
      fontSize: 13,
      theme: {
        background: '#0b1217',
        foreground: '#d7e3ea'
      }
    });

    const fitAddon = new FitAddon();
    term.loadAddon(fitAddon);

    terminalRef.current = term;
    fitRef.current = fitAddon;

    if (containerRef.current) {
      term.open(containerRef.current);
      fitAddon.fit();
      term.writeln('Terminal ready. Выберите VM и нажмите Connect.');
    }

    const onResize = (): void => fitAddon.fit();
    window.addEventListener('resize', onResize);

    return () => {
      window.removeEventListener('resize', onResize);
      disposeSocket();
      term.dispose();
      terminalRef.current = null;
      fitRef.current = null;
    };
  }, []);

  const connect = (): void => {
    const term = terminalRef.current;
    if (!term || !selectedVm) {
      return;
    }

    disposeSocket();
    term.clear();

    const wsUrl = `${WS_BASE_URL}/api/v1/vms/${selectedVm.id}/terminal?token=${encodeURIComponent(token)}`;
    const ws = new WebSocket(wsUrl);
    socketRef.current = ws;

    ws.onopen = () => {
      setConnected(true);
      term.focus();
    };

    ws.onmessage = (event) => {
      term.write(String(event.data));
    };

    ws.onclose = () => {
      setConnected(false);
      term.writeln('\r\nDisconnected');
    };

    ws.onerror = () => {
      term.writeln('\r\nTerminal socket error');
    };

    dataListenerRef.current = term.onData((data) => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(data);
      }
    });
  };

  return (
    <section className="panel">
      <div className="panel-head">
        <h2>SSH-консоль VM</h2>
        <div className="actions">
          <button className="btn" onClick={connect} disabled={!selectedVm}>
            Connect
          </button>
          <button className="btn btn-secondary" onClick={disposeSocket} disabled={!connected}>
            Disconnect
          </button>
        </div>
      </div>
      <p className="hint">WS endpoint: <strong>{WS_BASE_URL}</strong>. VM: <strong>{selectedVm?.name ?? 'не выбрана'}</strong></p>
      <div className="terminal-shell" ref={containerRef} />
    </section>
  );
}
