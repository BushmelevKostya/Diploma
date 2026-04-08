import { useMemo, useState } from 'react';
import { api } from '../../lib/api';
import { StatusBadge } from '../common/StatusBadge';
import type { CreateVmRequest, VmResponse } from '../../types/api';

interface Props {
  vmList: VmResponse[];
  onChanged: () => Promise<void>;
  onSelectVm: (vm: VmResponse) => void;
  selectedVmId?: string;
}

const defaultForm: CreateVmRequest = {
  name: '',
  hostname: '',
  vcpu: 1,
  memoryMb: 1024,
  diskSizeGb: 10,
  osImage: 'ubuntu-22.04'
};

export function VmSection({ vmList, onChanged, onSelectVm, selectedVmId }: Props): JSX.Element {
  const [form, setForm] = useState<CreateVmRequest>(defaultForm);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const sorted = useMemo(
    () => [...vmList].sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? '')),
    [vmList]
  );

  const onCreate = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setLoading(true);
    try {
      await api.createVm({
        ...form,
        hostname: form.hostname?.trim() || undefined
      });
      setForm(defaultForm);
      await onChanged();
    } finally {
      setLoading(false);
    }
  };

  const onAction = async (id: string, action: 'start' | 'stop' | 'delete') => {
    setBusyId(id);
    try {
      if (action === 'start') {
        await api.startVm(id);
      }
      if (action === 'stop') {
        await api.stopVm(id);
      }
      if (action === 'delete') {
        await api.deleteVm(id);
      }
      await onChanged();
    } finally {
      setBusyId(null);
    }
  };

  return (
    <section className="panel">
      <div className="panel-head">
        <h2>Стенды (Virtual Machines)</h2>
      </div>

      <form className="form-grid" onSubmit={onCreate}>
        <label>
          Название
          <input
            value={form.name}
            onChange={(event) => setForm((prev) => ({ ...prev, name: event.target.value }))}
            required
          />
        </label>
        <label>
          Hostname
          <input
            value={form.hostname}
            onChange={(event) => setForm((prev) => ({ ...prev, hostname: event.target.value }))}
          />
        </label>
        <label>
          vCPU
          <input
            type="number"
            min={1}
            max={16}
            value={form.vcpu}
            onChange={(event) =>
              setForm((prev) => ({ ...prev, vcpu: Number(event.target.value) || prev.vcpu }))
            }
            required
          />
        </label>
        <label>
          Memory (MB)
          <input
            type="number"
            min={512}
            max={32768}
            value={form.memoryMb}
            onChange={(event) =>
              setForm((prev) => ({ ...prev, memoryMb: Number(event.target.value) || prev.memoryMb }))
            }
            required
          />
        </label>
        <label>
          Disk (GB)
          <input
            type="number"
            min={5}
            max={500}
            value={form.diskSizeGb}
            onChange={(event) =>
              setForm((prev) => ({ ...prev, diskSizeGb: Number(event.target.value) || prev.diskSizeGb }))
            }
            required
          />
        </label>
        <label>
          OS Image
          <input
            value={form.osImage}
            onChange={(event) => setForm((prev) => ({ ...prev, osImage: event.target.value }))}
            required
          />
        </label>
        <button className="btn" disabled={loading}>
          {loading ? 'Создание...' : 'Создать стенд'}
        </button>
      </form>

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Имя</th>
              <th>IP</th>
              <th>Ресурсы</th>
              <th>Статус</th>
              <th>Действия</th>
            </tr>
          </thead>
          <tbody>
            {sorted.map((vm) => (
              <tr
                key={vm.id}
                className={selectedVmId === vm.id ? 'row-active' : ''}
                onClick={() => onSelectVm(vm)}
              >
                <td>
                  <strong>{vm.name}</strong>
                  <span className="sub">{vm.hostname ?? '-'}</span>
                </td>
                <td>{vm.ipAddress ?? '-'}</td>
                <td>{vm.vcpu} vCPU / {vm.memoryMb} MB / {vm.diskSizeGb} GB</td>
                <td>
                  <StatusBadge status={vm.status} />
                </td>
                <td className="actions">
                  <button
                    className="btn btn-secondary"
                    onClick={(event) => {
                      event.stopPropagation();
                      void onAction(vm.id, 'start');
                    }}
                    disabled={busyId === vm.id}
                  >
                    Start
                  </button>
                  <button
                    className="btn btn-secondary"
                    onClick={(event) => {
                      event.stopPropagation();
                      void onAction(vm.id, 'stop');
                    }}
                    disabled={busyId === vm.id}
                  >
                    Stop
                  </button>
                  <button
                    className="btn btn-danger"
                    onClick={(event) => {
                      event.stopPropagation();
                      void onAction(vm.id, 'delete');
                    }}
                    disabled={busyId === vm.id}
                  >
                    Delete
                  </button>
                </td>
              </tr>
            ))}
            {sorted.length === 0 && (
              <tr>
                <td colSpan={5} className="empty">
                  ВМ пока не создано
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}
