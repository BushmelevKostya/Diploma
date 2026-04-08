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
              <tr key={report.id}>
                <td>{report.vmName ?? report.vmId}</td>
                <td>
                  <StatusBadge status={report.status} />
                </td>
                <td>{report.differences?.length ?? 0}</td>
                <td>{report.checkedAt ? new Date(report.checkedAt).toLocaleString() : '-'}</td>
              </tr>
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
    </section>
  );
}
