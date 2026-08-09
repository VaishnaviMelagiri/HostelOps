import { BrowserRouter } from 'react-router-dom';
import { AuthProvider } from './auth/AuthContext';
import { AppRoutes } from './routes';

/**
 * AuthProvider sits OUTSIDE BrowserRouter's routes so the session is established once, not
 * re-checked on every navigation — and so ProtectedRoute can read it while deciding what to render.
 */
export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    </AuthProvider>
  );
}
