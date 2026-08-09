import { Navigate, Route, Routes } from 'react-router-dom';
import { LoginPage } from './auth/LoginPage';
import { ProtectedRoute } from './auth/ProtectedRoute';
import { HomePage } from './features/home/HomePage';
import { MapPage } from './features/map/MapPage';

/**
 * Route table.
 *
 * Only two routes in Phase 2. Phase 3 adds /map, Phase 8 the dashboards — they slot in here, each
 * wrapped in ProtectedRoute with the permissions that page needs.
 */
export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      <Route
        path="/"
        element={
          <ProtectedRoute>
            <HomePage />
          </ProtectedRoute>
        }
      />

      <Route
        path="/map"
        element={
          // All three roles hold ROOM_READ, so nobody is turned away here - but naming the
          // permission keeps the rule visible instead of implied.
          <ProtectedRoute requires={['ROOM_READ']}>
            <MapPage />
          </ProtectedRoute>
        }
      />

      {/* Anything unrecognised goes home, which in turn bounces to /login if not signed in. */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
