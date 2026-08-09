import { useContext } from 'react';
import { AuthContext } from './AuthContext';

/**
 * Access the current auth state.
 *
 * Throws rather than returning null if used outside AuthProvider. That turns a mistake which would
 * otherwise surface as a confusing "cannot read property user of null" deep inside a component into
 * a message naming the actual problem.
 */
export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside <AuthProvider>');
  }
  return context;
}
