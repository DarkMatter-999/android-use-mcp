import { z } from 'zod';
import { PairingRejectedError, RequestTimeoutError, TransportError } from '@android-use/transport';

export interface McpToolDependencies {
  status(): Promise<unknown>;
  pair(input: { code: string; hostName: string }): Promise<unknown>;
  snapshot(input: { mode: 'full' | 'compact' | 'actionable' }): Promise<unknown>;
  execute(code: string, timeoutMs: number): Promise<unknown>;
  disconnect(): Promise<unknown>;
}

const noInputSchema = z.object({}).strict();
const safeHostName = z.string()
  .trim()
  .min(1)
  .max(64)
  .refine((name) => !/[\u0000-\u001f\u007f]/.test(name));

export const statusToolSchema = noInputSchema;
export const disconnectToolSchema = noInputSchema;
export const pairToolSchema = z.object({
  code: z.string().trim().regex(/^[A-HJ-NP-Z2-9]{8}$/i).transform((code) => code.toUpperCase()),
  hostName: safeHostName,
}).strict();
export const snapshotToolSchema = z.object({
  mode: z.enum(['full', 'compact', 'actionable']).default('full'),
}).strict();
export const executeToolSchema = z.object({
  code: z.string().min(1).max(100_000),
  timeoutMs: z.number().int().min(50).max(60_000).default(1_000),
}).strict();

export function createToolHandlers(dependencies: McpToolDependencies) {
  return {
    android_status: async (input: unknown) => {
      statusToolSchema.parse(input);
      return dependencies.status();
    },
    android_pair: async (input: unknown) => dependencies.pair(pairToolSchema.parse(input)),
    android_snapshot: async (input: unknown) => dependencies.snapshot(snapshotToolSchema.parse(input)),
    android_execute: async (input: unknown) => {
      const parsed = executeToolSchema.parse(input);
      return dependencies.execute(parsed.code, parsed.timeoutMs);
    },
    android_disconnect: async (input: unknown) => {
      disconnectToolSchema.parse(input);
      return dependencies.disconnect();
    },
  };
}

export function formatToolError(error: unknown): string {
  if (error instanceof z.ZodError) {
    return 'Tool input is invalid. Check the required fields and documented value limits.';
  }
  if (error instanceof PairingRejectedError) {
    return error.status === 'EXPIRED'
      ? 'The pairing request expired. Display a new code on the phone and retry.'
      : 'The phone rejected the pairing request. Confirm the host details on the phone before retrying.';
  }
  if (error instanceof Error && error.message === 'phone approval timed out; pairing code was not resubmitted') {
    return 'Phone approval timed out. Display a fresh pairing code on the phone and retry.';
  }
  if (error instanceof RequestTimeoutError) {
    return 'The operation timed out. Check the device state before retrying a non-idempotent action.';
  }
  if (error instanceof TransportError) {
    return 'The Android device connection was lost. Check the configured connection and retry.';
  }
  const code = errorCode(error);
  switch (code) {
    case 'PAIRING_REQUIRED':
    case 'UNAUTHORIZED':
      return 'Pair this host and approve its certificate on the Android device, then retry.';
    case 'DISCONNECTED':
    case 'CONNECT_FAILED':
    case 'CONNECT_TIMEOUT':
      return 'The Android device is disconnected. Check the configured connection and retry.';
    case 'AMBIGUOUS_TARGET':
      return 'The selector matched multiple elements. Narrow it with a role, name, or additional filter.';
    case 'STALE_REF':
      return 'The element reference is stale. Fetch a new snapshot and locate the element again.';
    case 'NOT_FOUND':
      return 'The requested device element was not found. Fetch a new snapshot and check the selector.';
    case 'NOT_ACTIONABLE':
      return 'The selected element does not support that action. Fetch a fresh snapshot and choose an actionable element.';
    case 'TIMEOUT':
      return 'The operation timed out. Check the device state before retrying a non-idempotent action.';
    case 'CANCELLED':
      return 'The operation was cancelled before completion.';
    case 'INVALID_MESSAGE':
      return 'The request was rejected as invalid. Check the tool input and try again.';
    default:
      return 'Android automation failed. Check device status and connection, then retry if safe.';
  }
}

function errorCode(error: unknown): string | undefined {
  if (typeof error !== 'object' || error === null || !('code' in error)) return undefined;
  const code = (error as { code?: unknown }).code;
  return typeof code === 'string' ? code : undefined;
}
