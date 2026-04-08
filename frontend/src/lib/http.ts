import { clearTokens, getAccessToken, getRefreshToken, setTokens } from './storage';
import type { ErrorResponse, JwtResponse } from '../types/api';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

interface RequestOptions extends RequestInit {
  skipAuth?: boolean;
}

async function refreshAccessToken(): Promise<string | null> {
  const refreshToken = getRefreshToken();
  if (!refreshToken) {
    return null;
  }

  const response = await fetch(`${API_BASE_URL}/api/v1/auth/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken })
  });

  if (!response.ok) {
    clearTokens();
    return null;
  }

  const data = (await response.json()) as JwtResponse;
  setTokens(data.accessToken, data.refreshToken);
  return data.accessToken;
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set('Content-Type', 'application/json');

  if (!options.skipAuth) {
    const accessToken = getAccessToken();
    if (accessToken) {
      headers.set('Authorization', `Bearer ${accessToken}`);
    }
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers
  });

  if (response.status === 401 && !options.skipAuth) {
    const newAccessToken = await refreshAccessToken();
    if (newAccessToken) {
      headers.set('Authorization', `Bearer ${newAccessToken}`);
      const retryResponse = await fetch(`${API_BASE_URL}${path}`, {
        ...options,
        headers
      });
      if (retryResponse.ok) {
        if (retryResponse.status === 204) {
          return undefined as T;
        }
        return (await retryResponse.json()) as T;
      }
      await handleError(retryResponse);
    }
  }

  if (!response.ok) {
    await handleError(response);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}

async function handleError(response: Response): Promise<never> {
  let message = `HTTP ${response.status}`;
  try {
    const payload = (await response.json()) as ErrorResponse;
    if (payload?.message) {
      message = payload.message;
    } else if (payload?.error) {
      message = payload.error;
    }
  } catch {
    // ignore parse errors
  }

  throw new Error(message);
}

export { API_BASE_URL };
