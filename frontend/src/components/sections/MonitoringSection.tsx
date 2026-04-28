import type { MonitoringOverviewResponse } from '../../types/api';

interface Props {
  overview: MonitoringOverviewResponse | null;
}

function formatPercent(value?: number): string {
  if (value == null || Number.isNaN(value)) {
    return '-';
  }
  return `${value.toFixed(1)}%`;
}

function formatMemory(usedMb?: number, totalMb?: number): string {
  if (usedMb == null || totalMb == null || totalMb <= 0) {
    return '-';
  }
  return `${usedMb} / ${totalMb} MB`;
}

export function MonitoringSection({ overview }: Props): JSX.Element {
  const cpu = overview?.cpuUsagePercent ?? 0;
  const mem = overview?.memoryUsagePercent ?? 0;

  return (
    <section className="panel monitoring-panel">
      <div className="panel-head">
        <h2>Мониторинг сервера</h2>
        <span className="hint">
          Обновлено: {overview?.collectedAt ? new Date(overview.collectedAt).toLocaleString() : '-'}
        </span>
      </div>

      <div className="monitoring-grid">
        <div className="monitoring-card">
          <span className="monitoring-label">Поднятые ВМ</span>
          <strong className="monitoring-value">{overview?.runningVmCount ?? '-'}</strong>
          <span className="monitoring-sub">Сейчас в состоянии RUNNING</span>
        </div>

        <div className="monitoring-card">
          <span className="monitoring-label">CPU сервера</span>
          <strong className="monitoring-value">{formatPercent(overview?.cpuUsagePercent)}</strong>
          <div className="meter">
            <div className="meter-fill" style={{ width: `${Math.min(100, Math.max(0, cpu))}%` }} />
          </div>
        </div>

        <div className="monitoring-card">
          <span className="monitoring-label">Память сервера</span>
          <strong className="monitoring-value">{formatPercent(overview?.memoryUsagePercent)}</strong>
          <span className="monitoring-sub">{formatMemory(overview?.memoryUsedMb, overview?.memoryTotalMb)}</span>
          <div className="meter">
            <div className="meter-fill meter-fill-memory" style={{ width: `${Math.min(100, Math.max(0, mem))}%` }} />
          </div>
        </div>
      </div>
    </section>
  );
}
