import { useEffect, useMemo, useState } from 'react';
import { api } from './lib/api';
import { API_BASE_URL } from './lib/http';
import { clearTokens, getAccessToken, setTokens } from './lib/storage';
import { Header } from './components/layout/Header';
import { VmSection } from './components/sections/VmSection';
import { DriftSection } from './components/sections/DriftSection';
import { SnapshotSection } from './components/sections/SnapshotSection';
import { TerminalSection } from './components/sections/TerminalSection';
import { MonitoringSection } from './components/sections/MonitoringSection';
import type { DriftReportResponse, SnapshotResponse, VmResponse, MonitoringOverviewResponse } from './types/api';

interface HealthInfo {
  status: string;
  service: string;
  timestamp: string;
}

export function App(): JSX.Element {
  const [token, setToken] = useState<string | null>(() => getAccessToken());
  const [username, setUsername] = useState('operator');
  const [password, setPassword] = useState('');
  const [authLoading, setAuthLoading] = useState(false);

  const [vmList, setVmList] = useState<VmResponse[]>([]);
  const [selectedVmId, setSelectedVmId] = useState<string | undefined>();
  const [driftReports, setDriftReports] = useState<DriftReportResponse[]>([]);
  const [referenceSnapshot, setReferenceSnapshot] = useState<SnapshotResponse | null>(null);
  const [monitoringOverview, setMonitoringOverview] = useState<MonitoringOverviewResponse | null>(null);
  const [health, setHealth] = useState<HealthInfo | null>(null);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const selectedVm = useMemo(
    () => vmList.find((vm) => vm.id === selectedVmId),
    [vmList, selectedVmId]
  );

  const loadData = async (): Promise<void> => {
    setLoading(true);
    setError(null);
    try {
      const [healthData, vmData, driftData, monitoringData] = await Promise.all([
        api.health(),
        api.listVms(0, 100),
        api.listDriftReports(0, 50),
        api.monitoringOverview()
      ]);
      let currentReferenceSnapshot: SnapshotResponse | null = null;
      try {
        currentReferenceSnapshot = await api.getReferenceSnapshot();
      } catch (referenceError) {
        const message = referenceError instanceof Error ? referenceError.message : '';
        if (!message.includes('Reference snapshot not set')) {
          throw referenceError;
        }
      }

      setHealth({
        status: healthData.status,
        service: healthData.service,
        timestamp: healthData.timestamp
      });
      setVmList(vmData.content);
      setDriftReports(driftData.content);
      setReferenceSnapshot(currentReferenceSnapshot);
      setMonitoringOverview(monitoringData);
      setSelectedVmId((currentSelectedVmId) => {
        if (vmData.content.length === 0) {
          return undefined;
        }

        const vmStillExists = currentSelectedVmId
          && vmData.content.some((vm) => vm.id === currentSelectedVmId);

        if (vmStillExists) {
          return currentSelectedVmId;
        }

        return vmData.content[0].id;
      });
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : 'Неизвестная ошибка');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (!token) {
      return;
    }

    void loadData();
    const timer = window.setInterval(() => {
      void loadData();
    }, 15000);

    return () => window.clearInterval(timer);
  }, [token]);

  const login = async (event: React.FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    setError(null);
    setAuthLoading(true);
    try {
      const result = await api.login({ username, password });
      setTokens(result.accessToken, result.refreshToken);
      setToken(result.accessToken);
    } catch (loginError) {
      setError(loginError instanceof Error ? loginError.message : 'Ошибка авторизации');
    } finally {
      setAuthLoading(false);
    }
  };

  const register = async (): Promise<void> => {
    setError(null);
    setAuthLoading(true);
    try {
      const result = await api.register({ username, password });
      setTokens(result.accessToken, result.refreshToken);
      setToken(result.accessToken);
    } catch (registerError) {
      setError(registerError instanceof Error ? registerError.message : 'Ошибка регистрации');
    } finally {
      setAuthLoading(false);
    }
  };

  const logout = (): void => {
    clearTokens();
    setToken(null);
    setVmList([]);
    setDriftReports([]);
    setReferenceSnapshot(null);
    setMonitoringOverview(null);
    setSelectedVmId(undefined);
  };

  const handleVmCreated = (vm: VmResponse): void => {
    setSelectedVmId(vm.id);
  };

  if (!token) {
    return (
      <main className="auth-layout">
        <section className="auth-card">
          <p className="eyebrow">Diploma Infrastructure</p>
          <h1>Вход в Control Plane</h1>
          <form onSubmit={(event) => void login(event)} className="auth-form">
            <label>
              Username
              <input value={username} onChange={(event) => setUsername(event.target.value)} required />
            </label>
            <label>
              Password
              <input
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                minLength={8}
                required
              />
            </label>
            <div className="actions">
              <button className="btn" type="submit" disabled={authLoading}>
                {authLoading ? 'Обработка...' : 'Войти'}
              </button>
              <button
                className="btn btn-secondary"
                type="button"
                onClick={() => void register()}
                disabled={authLoading}
              >
                Зарегистрироваться
              </button>
            </div>
          </form>
          <p className="hint">Используются endpoints: {API_BASE_URL}/api/v1/auth/login и /api/v1/auth/register</p>
          {error && <p className="error-box">{error}</p>}
        </section>
      </main>
    );
  }

  return (
    <main className="app-shell">
      <Header username={username} onLogout={logout} apiBaseUrl={API_BASE_URL} />

      <section className="status-row">
        <div className="status-card">
          <span>Backend status</span>
          <strong>{health?.status ?? '...'}</strong>
        </div>
        <div className="status-card">
          <span>VM total</span>
          <strong>{vmList.length}</strong>
        </div>
        <div className="status-card">
          <span>Последняя проверка</span>
          <strong>{health?.timestamp ? new Date(health.timestamp).toLocaleString() : '-'}</strong>
        </div>
      </section>

      <MonitoringSection overview={monitoringOverview} />

      {error && <p className="error-box">{error}</p>}
      {loading && <p className="hint">Загрузка данных...</p>}

      <div className="grid-two">
        <VmSection
          vmList={vmList}
          onChanged={loadData}
          onCreated={handleVmCreated}
          onSelectVm={(vm) => setSelectedVmId(vm.id)}
          selectedVmId={selectedVmId}
        />
        <DriftSection
          vmList={vmList}
          reports={driftReports}
          selectedVmId={selectedVmId}
          referenceSnapshot={referenceSnapshot}
          onChanged={loadData}
        />
      </div>

      <div className="grid-two">
        <SnapshotSection selectedVm={selectedVm} referenceSnapshot={referenceSnapshot} onChanged={loadData} />
        <TerminalSection selectedVm={selectedVm} />
      </div>
    </main>
  );
}
