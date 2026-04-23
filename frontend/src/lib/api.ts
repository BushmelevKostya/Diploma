import { apiRequest } from './http';
import type {
  CreateSnapshotRequest,
  CreateVmRequest,
  DriftReportResponse,
  EnvironmentPackageOptionResponse,
  JwtResponse,
  LoginRequest,
  PageDriftReportResponse,
  PageVmResponse,
  RegisterRequest,
  SnapshotResponse,
  VmResponse
} from '../types/api';

export const api = {
  login: (payload: LoginRequest) =>
    apiRequest<JwtResponse>('/api/v1/auth/login', {
      method: 'POST',
      skipAuth: true,
      body: JSON.stringify(payload)
    }),

  register: (payload: RegisterRequest) =>
    apiRequest<JwtResponse>('/api/v1/auth/register', {
      method: 'POST',
      skipAuth: true,
      body: JSON.stringify(payload)
    }),

  health: () =>
    apiRequest<{ status: string; service: string; timestamp: string; details: Record<string, unknown> }>(
      '/api/v1/health',
      { skipAuth: true }
    ),

  listVms: (page = 0, size = 50) =>
    apiRequest<PageVmResponse>(`/api/v1/vms?page=${page}&size=${size}`),

  createVm: (payload: CreateVmRequest) =>
    apiRequest<VmResponse>('/api/v1/vms', {
      method: 'POST',
      body: JSON.stringify(payload)
    }),

  listEnvironmentPackages: () =>
    apiRequest<EnvironmentPackageOptionResponse[]>('/api/v1/vms/environment-packages'),

  deleteVm: (id: string) =>
    apiRequest<void>(`/api/v1/vms/${id}`, {
      method: 'DELETE'
    }),

  startVm: (id: string) =>
    apiRequest<VmResponse>(`/api/v1/vms/${id}/start`, {
      method: 'POST'
    }),

  stopVm: (id: string) =>
    apiRequest<VmResponse>(`/api/v1/vms/${id}/stop`, {
      method: 'POST'
    }),

  checkDrift: (vmId: string) =>
    apiRequest<DriftReportResponse>(`/api/v1/vms/${vmId}/drift`, {
      method: 'POST'
    }),

  listDriftReports: (page = 0, size = 30) =>
    apiRequest<PageDriftReportResponse>(`/api/v1/drift-reports?page=${page}&size=${size}`),

  listSnapshots: (vmId: string) => apiRequest<SnapshotResponse[]>(`/api/v1/vms/${vmId}/snapshots`),

  createSnapshot: (vmId: string, payload: CreateSnapshotRequest) =>
    apiRequest<SnapshotResponse>(`/api/v1/vms/${vmId}/snapshots`, {
      method: 'POST',
      body: JSON.stringify(payload)
    }),

  restoreSnapshot: (vmId: string, snapshotId: string) =>
    apiRequest<VmResponse>(`/api/v1/vms/${vmId}/snapshots/${snapshotId}/restore`, {
      method: 'POST'
    }),

  deleteSnapshot: (vmId: string, snapshotId: string) =>
    apiRequest<void>(`/api/v1/vms/${vmId}/snapshots/${snapshotId}`, {
      method: 'DELETE'
    })
};
