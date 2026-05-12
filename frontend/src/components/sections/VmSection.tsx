import { useEffect, useMemo, useState } from 'react';
import { api } from '../../lib/api';
import { StatusBadge } from '../common/StatusBadge';
import type {
  CreateVmRequest,
  EnvironmentPackage,
  EnvironmentPackageOptionResponse,
  OsImage,
  VmResponse
} from '../../types/api';

interface Props {
  vmList: VmResponse[];
  onChanged: () => Promise<void>;
  onCreated: (vm: VmResponse) => void;
  onSelectVm: (vm: VmResponse) => void;
  selectedVmId?: string;
}

interface OsProfile {
  label: string;
  vcpu: number;
  memoryMb: number;
  diskSizeGb: number;
  minMemoryMb: number;
  minDiskGb: number;
}

const OS_PROFILES: Record<OsImage, OsProfile> = {
  ubuntu_22_04: {
    label: 'Ubuntu',
    vcpu: 1,
    memoryMb: 1024,
    diskSizeGb: 10,
    minMemoryMb: 512,
    minDiskGb: 5
  },
  alpine_3_19: {
    label: 'Alpine',
    vcpu: 1,
    memoryMb: 256,
    diskSizeGb: 2,
    minMemoryMb: 192,
    minDiskGb: 2
  }
};

const defaultOs: OsImage = 'ubuntu_22_04';
const defaultForm: CreateVmRequest = {
  name: '',
  hostname: '',
  vcpu: OS_PROFILES[defaultOs].vcpu,
  memoryMb: OS_PROFILES[defaultOs].memoryMb,
  diskSizeGb: OS_PROFILES[defaultOs].diskSizeGb,
  osImage: defaultOs,
  environmentPackages: []
};

export function VmSection({ vmList, onChanged, onCreated, onSelectVm, selectedVmId }: Props): JSX.Element {
  const [form, setForm] = useState<CreateVmRequest>(defaultForm);
  const [environmentOptions, setEnvironmentOptions] = useState<EnvironmentPackageOptionResponse[]>([]);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const currentProfile = OS_PROFILES[form.osImage];

  useEffect(() => {
    void api.listEnvironmentPackages().then(setEnvironmentOptions);
  }, []);

  const sorted = useMemo(
    () => [...vmList].sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? '')),
    [vmList]
  );

  const onCreate = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setLoading(true);
    try {
      const createdVm = await api.createVm({
        ...form,
        hostname: form.hostname?.trim() || undefined
      });
      onCreated(createdVm);
      setForm({ ...defaultForm });
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

  const toggleEnvironmentPackage = (environmentPackage: EnvironmentPackage) => {
    setForm((prev) => {
      const alreadySelected = prev.environmentPackages.includes(environmentPackage);
      return {
        ...prev,
        environmentPackages: alreadySelected
          ? prev.environmentPackages.filter((item) => item !== environmentPackage)
          : [...prev.environmentPackages, environmentPackage]
      };
    });
  };

  const handleOsChange = (osImage: OsImage) => {
    const profile = OS_PROFILES[osImage];
    setForm((prev) => ({
      ...prev,
      osImage,
      vcpu: profile.vcpu,
      memoryMb: profile.memoryMb,
      diskSizeGb: profile.diskSizeGb
    }));
  };

  return (
    <section className="panel">
      <div className="panel-head">
        <h2>Виртуальные машины</h2>
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
            min={currentProfile.minMemoryMb}
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
            min={currentProfile.minDiskGb}
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
          <select
            value={form.osImage}
            onChange={(event) => handleOsChange(event.target.value as OsImage)}
            required
          >
            {(Object.entries(OS_PROFILES) as Array<[OsImage, OsProfile]>).map(([value, profile]) => (
              <option key={value} value={value}>
                {profile.label}
              </option>
            ))}
          </select>
        </label>
        <div className="option-group">
          <span className="option-group-title">Окружение</span>
          <div className="option-grid">
            {environmentOptions.map((option) => (
              <label key={option.code} className="checkbox-card">
                <input
                  type="checkbox"
                  checked={form.environmentPackages.includes(option.code)}
                  onChange={() => toggleEnvironmentPackage(option.code)}
                />
                <span>
                  <strong>{option.title}</strong>
                </span>
              </label>
            ))}
          </div>
        </div>
        <button className="btn" disabled={loading}>
          {loading ? 'Создание...' : 'Создать ВМ'}
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
                <span className="sub">
                    {vm.environmentPackages.length > 0 ? vm.environmentPackages.join(', ') : 'base only'}
                  </span>
              </td>
              <td>
                <strong>{vm.ipAddress ?? '-'}</strong>
                <span className="sub">{vm.statusMessage ?? 'No details yet'}</span>
              </td>
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
                  disabled={busyId === vm.id || vm.status === 'CREATING'}
                >
                  Start
                </button>
                <button
                  className="btn btn-secondary"
                  onClick={(event) => {
                    event.stopPropagation();
                    void onAction(vm.id, 'stop');
                  }}
                  disabled={busyId === vm.id || vm.status === 'CREATING'}
                >
                  Stop
                </button>
                <button
                  className="btn btn-danger"
                  onClick={(event) => {
                    event.stopPropagation();
                    void onAction(vm.id, 'delete');
                  }}
                  disabled={busyId === vm.id || vm.status === 'CREATING'}
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
