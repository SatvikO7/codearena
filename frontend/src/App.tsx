import { BrowserRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider } from './contexts/AuthProvider';
import { ProtectedRoute } from './components/ProtectedRoute';
import { AppLayout } from './layouts/AppLayout';
import { HomePage } from './pages/HomePage';
import { LoginPage } from './pages/LoginPage';
import { RegisterPage } from './pages/RegisterPage';
import { ProfilePage } from './pages/ProfilePage';
import { ProblemsPage } from './pages/ProblemsPage';
import { ProblemDetailPage } from './pages/ProblemDetailPage';
import { SolvePage } from './pages/SolvePage';
import { SubmissionsPage } from './pages/SubmissionsPage';
import { ContestsPage } from './pages/ContestsPage';
import { ContestDetailPage } from './pages/ContestDetailPage';
import { ContestSolvePage } from './pages/ContestSolvePage';
import { SubmissionDetailPage } from './pages/SubmissionDetailPage';
import { AdminProblemsPage } from './pages/admin/AdminProblemsPage';
import { AdminProblemFormPage } from './pages/admin/AdminProblemFormPage';
import { AdminContestsPage } from './pages/admin/AdminContestsPage';
import { AdminContestDetailPage } from './pages/admin/AdminContestDetailPage';
import { AdminAuditPage } from './pages/admin/AdminAuditPage';
import { AdminSystemPage } from './pages/admin/AdminSystemPage';
import { NotFoundPage } from './pages/NotFoundPage';

export default function App() {
  return (
    <BrowserRouter>
      {/* Inside the router so auth-aware screens can navigate. */}
      <AuthProvider>
        <Routes>
          <Route element={<AppLayout />}>
            <Route index element={<HomePage />} />
            <Route path="login" element={<LoginPage />} />
            <Route path="register" element={<RegisterPage />} />

            {/* Requires a session. The server enforces this independently. */}
            <Route element={<ProtectedRoute />}>
              <Route path="profile" element={<ProfilePage />} />
              <Route path="problems" element={<ProblemsPage />} />
              <Route path="problems/:slug" element={<ProblemDetailPage />} />
              <Route path="problems/:slug/solve" element={<SolvePage />} />
              <Route path="submissions" element={<SubmissionsPage />} />
              <Route path="submissions/:submissionId" element={<SubmissionDetailPage />} />
              <Route path="contests" element={<ContestsPage />} />
              <Route path="contests/:contestId" element={<ContestDetailPage />} />
              {/* A separate route from the practice solve page on purpose: it posts to
                  the contest endpoint, and a contest page that created a practice
                  submission would judge it and never score it. */}
              <Route
                path="contests/:contestId/problems/:problemId"
                element={<ContestSolvePage />}
              />
            </Route>

            {/* Requires ADMIN. Again a convenience: every admin endpoint re-checks. */}
            <Route element={<ProtectedRoute requireRole="ADMIN" />}>
              <Route path="admin/problems" element={<AdminProblemsPage />} />
              <Route path="admin/problems/new" element={<AdminProblemFormPage />} />
              <Route path="admin/problems/:problemId" element={<AdminProblemFormPage />} />
              <Route path="admin/contests" element={<AdminContestsPage />} />
              <Route path="admin/contests/:contestId" element={<AdminContestDetailPage />} />
              {/* Administrative-only, and reachable only from the admin navigation:
                  the audit log and the operational view are not part of the normal
                  user surface. The server authorises independently. */}
              <Route path="admin/audit" element={<AdminAuditPage />} />
              <Route path="admin/system" element={<AdminSystemPage />} />
            </Route>

            <Route path="*" element={<NotFoundPage />} />
          </Route>
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}
