interface Props {
  username: string;
  onLogout: () => void;
  apiBaseUrl: string;
}

export function Header({ username, onLogout, apiBaseUrl }: Props): JSX.Element {
  return (
    <header className="header">
      <div>
        <p className="eyebrow">Infrastructure Control Plane</p>
        <h1>Управление стендами и сервисами</h1>
      </div>
      <div className="header-right">
        <p className="connection">API: {apiBaseUrl}</p>
        <p className="user">{username}</p>
        <button className="btn btn-secondary" onClick={onLogout}>
          Выйти
        </button>
      </div>
    </header>
  );
}
