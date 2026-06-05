interface Props {
  username: string;
  onLogout: () => void;
  apiBaseUrl: string;
}

export function Header({ username, onLogout, apiBaseUrl }: Props): JSX.Element {
  return (
    <header className="header">
      <div>
        <h1>Infrastructure Control Plane</h1>
      </div>
      <div className="header-right">
        <p className="user">{username}</p>
        <button className="btn btn-secondary" onClick={onLogout}>
          Выйти
        </button>
      </div>
    </header>
  );
}
