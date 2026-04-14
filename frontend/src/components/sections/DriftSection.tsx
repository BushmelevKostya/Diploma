import { useState } from 'react';
import { api } from '../../lib/api';
import { StatusBadge } from '../common/StatusBadge';
import type { DriftReportResponse, VmResponse } from '../../types/api';

interface Props {
  vmList: VmResponse[];
  reports: DriftReportResponse[];
  selectedVmId?: string;
  onChanged: () => Promise<void>;
}

export function DriftSection({ vmList, reports, selectedVmId, onChanged }: Props): JSX.Element {
  const [busy, setBusy] = useState(false);
  const [expandedReportId, setExpandedReportId] = useState<string | null>(null);

  const selectedVm = vmList.find((vm) => vm.id === selectedVmId) ?? vmList[0];

  const runDrift = async (): Promise<void> => {
    if (!selectedVm) {
      return;
    }

    setBusy(true);
    try {
      await api.checkDrift(selectedVm.id);
      await onChanged();
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="panel">
      <div className="panel-head">
        <h2>Drift detection</h2>
        <button className="btn" onClick={() => void runDrift()} disabled={!selectedVm || busy}>
          {busy ? 'Проверка...' : 'Запустить проверку'}
        </button>
      </div>

      <div className="hint">
        Активная VM: <strong>{selectedVm?.name ?? 'не выбрана'}</strong>
      </div>

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>VM</th>
              <th>Статус</th>
              <th>Diff</th>
              <th>Checked at</th>
            </tr>
          </thead>
          <tbody>
            {reports.map((report) => (
              <>
                <tr 
                  key={report.id} 
                  className="drift-row-clickable" 
                  onClick={() => setExpandedReportId(expandedReportId === report.id ? null : report.id)}
                >
                  <td style={{ cursor: 'pointer' }}>
                    {expandedReportId === report.id ? '▼' : '▶'} {report.vmName ?? report.vmId}
                  </td>
                  <td>
                    <StatusBadge status={report.status} />
                  </td>
                  <td style={{ cursor: 'pointer' }}>
                    {report.differences?.length ?? 0} расхождений
                  </td>
                  <td>{report.checkedAt ? new Date(report.checkedAt).toLocaleString() : '-'}</td>
                </tr>
                {expandedReportId === report.id && report.differences && report.differences.length > 0 && (
                  <tr key={`${report.id}-details`} className="drift-details-row">
                    <td colSpan={4}>
                      <div className="drift-details-content">
                        <h4>Расхождения конфигурации:</h4>
                        <table className="diff-table">
                          <thead>
                            <tr>
                              <th>Параметр</th>
                              <th>Ожидалось</th>
                              <th>Фактически</th>
                            </tr>
                          </thead>
                          <tbody>
                            {report.differences.map((diff, idx) => (
                              <tr key={idx}>
                                <td className="field-name">{diff.field}</td>
                                <td className="expected-value">{diff.expected}</td>
                                <td className="actual-value">{diff.actual}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    </td>
                  </tr>
                )}
                {expandedReportId === report.id && (!report.differences || report.differences.length === 0) && (
                  <tr key={`${report.id}-clean`} className="drift-details-row">
                    <td colSpan={4}>
                      <div className="drift-details-content">
                        <p style={{ color: '#28a745', fontSize: '0.9rem' }}>✓ Конфигурация соответствует ожидаемому состоянию</p>
                      </div>
                    </td>
                  </tr>
                )}
              </>
            ))}
            {reports.length === 0 && (
              <tr>
                <td colSpan={4} className="empty">
                  Drift-отчётов пока нет
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <style>{`
        .drift-row-clickable {
          cursor: pointer;
        }
        .drift-row-clickable:hover {
          background-color: #f9f9f9;
        }
        .drift-details-row {
          background-color: #f5f5f5;
        }
        .drift-details-content {
          padding: 16px;
        }
        .drift-details-content h4 {
          margin: 0 0 12px;
          font-size: 0.95rem;
          color: #333;
          font-weight: 600;
        }
        .diff-table {
          width: 100%;
          border-collapse: collapse;
          font-size: 0.85rem;
          margin-top: 8px;
        }
        .diff-table thead tr {
          background-color: #e9ecef;
        }
        .diff-table th,
        .diff-table td {
          padding: 8px 12px;
          border: 1px solid #ddd;
          text-align: left;
        }
        .diff-table .field-name {
          font-weight: 600;
          width: 25%;
          color: #333;
        }
        .diff-table .expected-value {
          color: #028a0f;
          background-color: #f0fdf4;
          font-family: monospace;
        }
        .diff-table .actual-value {
          color: #dc2626;
          background-color: #fef2f2;
          font-family: monospace;
        }
      `}</style>
    </section>
  );
}
