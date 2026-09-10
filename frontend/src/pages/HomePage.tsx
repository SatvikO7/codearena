import { useSystemInfo } from '../hooks/useSystemInfo';
import { ConnectionStatus } from '../components/ConnectionStatus';

export function HomePage() {
  const connection = useSystemInfo();

  return (
    <div className="page">
      <section className="hero">
        <h1>CodeArena</h1>
        <p className="lede">
          An online judge that compiles and runs untrusted submissions inside disposable,
          network-isolated containers, judged asynchronously by a pool of workers.
        </p>
      </section>

      <section aria-labelledby="status-heading" className="panel">
        <h2 id="status-heading">Platform status</h2>
        <ConnectionStatus state={connection} />
      </section>
    </div>
  );
}
