import { Link } from 'react-router-dom';

export function NotFoundPage() {
  return (
    <div className="page">
      <section className="panel empty-state">
        <h1>Page not found</h1>
        <p>The page you asked for does not exist.</p>
        <Link className="button" to="/">
          Back to home
        </Link>
      </section>
    </div>
  );
}
