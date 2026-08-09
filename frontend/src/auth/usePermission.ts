import type { Permission } from '../api/types';
import { useAuth } from './useAuth';

/**
 * True when the signed-in user holds every permission given.
 *
 * Use this to decide what to RENDER, never to decide what is allowed. A GUEST should not be shown a
 * "Request this bed" button they would only get a 403 from — but the reason they cannot request a
 * bed is that the server refuses, not that the button is hidden. Anyone can edit the JavaScript;
 * nobody can edit the @PreAuthorize check.
 *
 * The permission list comes from the server, resolved from the same RolePermissions map the backend
 * enforces, so the UI and the API can never disagree about what a role can do.
 */
export function usePermission(...permissions: Permission[]): boolean {
  return useAuth().can(...permissions);
}
