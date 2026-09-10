import type { ConnectionState } from '../hooks/useSystemInfo';

interface Props {
  state: ConnectionState;
}

/** Renders the three states of the API connectivity probe: loading, connected, failed. */
export function ConnectionStatus({ state }: Props) {
  if (state.status === 'loading') {
    return (
      <p className="status status--pending" role="status">
        Checking connection to the API server&hellip;
      </p>
    );
  }

  if (state.status === 'error') {
    return (
      <div role="alert">
        <p className="status status--error">API server unreachable</p>
        <p className="status-detail">{state.message}</p>
      </div>
    );
  }

  const { info } = state;
  return (
    <div>
      <p className="status status--ok" role="status">
        Connected to the API server
      </p>
      <dl className="detail-grid">
        <div>
          <dt>Service</dt>
          <dd>{info.service}</dd>
        </div>
        <div>
          <dt>Version</dt>
          <dd>{info.version}</dd>
        </div>
        <div>
          <dt>Profiles</dt>
          <dd>{info.profiles.length > 0 ? info.profiles.join(', ') : 'default'}</dd>
        </div>
        <div>
          <dt>Server time</dt>
          <dd>{new Date(info.serverTime).toLocaleString()}</dd>
        </div>
      </dl>
    </div>
  );
}
