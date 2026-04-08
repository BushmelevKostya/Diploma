import type { DriftStatus, SnapshotStatus, VmStatus } from '../../types/api';

type Status = VmStatus | DriftStatus | SnapshotStatus | 'UP' | 'DOWN' | 'DEGRADED';

interface Props {
  status: Status;
}

const statusClassMap: Record<Status, string> = {
  CREATING: 'status status-warn',
  RUNNING: 'status status-ok',
  STOPPED: 'status status-neutral',
  ERROR: 'status status-err',
  CLEAN: 'status status-ok',
  DRIFTED: 'status status-err',
  UNKNOWN: 'status status-neutral',
  READY: 'status status-ok',
  RESTORING: 'status status-warn',
  FAILED: 'status status-err',
  UP: 'status status-ok',
  DOWN: 'status status-err',
  DEGRADED: 'status status-warn'
};

export function StatusBadge({ status }: Props): JSX.Element {
  return <span className={statusClassMap[status]}>{status}</span>;
}
