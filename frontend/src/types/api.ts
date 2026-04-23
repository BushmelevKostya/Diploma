export type UserRole = 'ADMIN' | 'OPERATOR' | 'VIEWER';
export type VmStatus = 'CREATING' | 'RUNNING' | 'STOPPED' | 'STARTING' | 'STOPPING' | 'ERROR';
export type DriftStatus = 'CLEAN' | 'DRIFTED' | 'UNKNOWN';
export type SnapshotStatus = 'CREATING' | 'READY' | 'RESTORING' | 'FAILED';
export type EnvironmentPackage = 'SSH' | 'DOCKER' | 'HTTP_SERVER';

export interface JwtResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  password: string;
}

export interface CreateVmRequest {
  name: string;
  hostname?: string;
  vcpu: number;
  memoryMb: number;
  diskSizeGb: number;
  osImage: string;
  environmentPackages: EnvironmentPackage[];
}

export interface VmResponse {
  id: string;
  name: string;
  hostname?: string;
  ipAddress?: string;
  statusMessage?: string;
  vcpu: number;
  memoryMb: number;
  diskSizeGb: number;
  osImage: string;
  environmentPackages: EnvironmentPackage[];
  status: VmStatus;
  createdAt?: string;
  updatedAt?: string;
}

export interface EnvironmentPackageOptionResponse {
  code: EnvironmentPackage;
  title: string;
  description: string;
}

export interface PageInfo {
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface PageVmResponse {
  content: VmResponse[];
  page: PageInfo;
}

export interface CreateSnapshotRequest {
  name: string;
  description?: string;
}

export interface SnapshotResponse {
  id: string;
  name: string;
  description?: string;
  status: SnapshotStatus;
  vmId: string;
  createdAt?: string;
}

export interface DriftDifference {
  field: string;
  expected: string;
  actual: string;
}

export interface DriftReportResponse {
  id: string;
  vmId: string;
  vmName?: string;
  status: DriftStatus;
  differences?: DriftDifference[];
  checkedAt?: string;
  createdAt?: string;
}

export interface PageDriftReportResponse {
  content: DriftReportResponse[];
  page: PageInfo;
}

export interface ErrorResponse {
  timestamp?: string;
  status?: number;
  error?: string;
  message?: string;
  path?: string;
}
