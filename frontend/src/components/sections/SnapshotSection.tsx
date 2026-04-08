import { useEffect, useState } from 'react';
import { api } from '../../lib/api';
import { StatusBadge } from '../common/StatusBadge';
import type { SnapshotResponse, VmResponse } from '../../types/api';

interface Props {
  selectedVm?: VmResponse;
}

export function SnapshotSection({ selectedVm }: Props): JSX.Element {
  const [items, setItems] = useState<SnapshotResponse[]>([]);
  const [busy, setBusy] = useState(false);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');

  const load = async (): Promise<void> => {
    if (!selectedVm) {
      setItems([]);
      return;
    }
    const snapshots = await api.listSnapshots(selectedVm.id);
    setItems(snapshots);
  };

  useEffect(() => {
    void load();
  }, [selectedVm?.id]);

  const create = async (): Promise<void> => {
    if (!selectedVm || !name.trim()) {
      return;
    }
    setBusy(true);
    try {
      await api.createSnapshot(selectedVm.id, { name: name.trim(), description: description.trim() || undefined });
      setName('');
      setDescription('');
      await load();
    } finally {
      setBusy(false);
    }
  };

  const restore = async (snapshotId: string): Promise<void> => {
    if (!selectedVm) {
      return;
    }
    setBusy(true);
    try {
      await api.restoreSnapshot(selectedVm.id, snapshotId);
      await load();
    } finally {
      setBusy(false);
    }
  };

  const remove = async (snapshotId: string): Promise<void> => {
    if (!selectedVm) {
      return;
    }
    setBusy(true);
    try {
      await api.deleteSnapshot(selectedVm.id, snapshotId);
      await load();
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="panel">
      <div className="panel-head">
        <h2>Снапшоты VM</h2>
      </div>
      <p className="hint">Выбранная VM: <strong>{selectedVm?.name ?? 'не выбрана'}</strong></p>

      <div className="inline-form">
        <input
          placeholder="snapshot name"
          value={name}
          onChange={(event) => setName(event.target.value)}
          disabled={!selectedVm}
        />
        <input
          placeholder="description"
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          disabled={!selectedVm}
        />
        <button className="btn" onClick={() => void create()} disabled={!selectedVm || busy || !name.trim()}>
          Создать
        </button>
      </div>

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Status</th>
              <th>Created</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {items.map((snapshot) => (
              <tr key={snapshot.id}>
                <td>{snapshot.name}</td>
                <td><StatusBadge status={snapshot.status} /></td>
                <td>{snapshot.createdAt ? new Date(snapshot.createdAt).toLocaleString() : '-'}</td>
                <td className="actions">
                  <button className="btn btn-secondary" onClick={() => void restore(snapshot.id)} disabled={busy}>
                    Restore
                  </button>
                  <button className="btn btn-danger" onClick={() => void remove(snapshot.id)} disabled={busy}>
                    Delete
                  </button>
                </td>
              </tr>
            ))}
            {items.length === 0 && (
              <tr>
                <td colSpan={4} className="empty">
                  Нет снапшотов для выбранной VM
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}
