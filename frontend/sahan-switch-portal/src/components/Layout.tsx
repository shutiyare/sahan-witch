import { NavLink, Outlet } from "react-router-dom";
import { LayoutDashboard, LogOut, Building2, ArrowLeftRight } from "lucide-react";
import { useAuth } from "../auth/AuthContext";

const LINKS = [
  { to: "/dashboard", label: "Dashboard", icon: LayoutDashboard },
  { to: "/transactions", label: "Transactions", icon: ArrowLeftRight },
  { to: "/participants", label: "Participants", icon: Building2 },
];

export function Layout() {
  const { session, logout } = useAuth();

  return (
    <div className="min-h-screen bg-slate-50">
      <header className="border-b border-slate-200 bg-slate-950 text-white">
        <div className="mx-auto flex max-w-7xl items-center justify-between gap-4 px-4 py-3">
          <div className="flex items-center gap-6">
            <p className="text-sm font-semibold tracking-wide">Sahan Switch</p>
            <nav className="flex gap-1">
              {LINKS.map((link) => (
                <NavLink
                  key={link.to}
                  to={link.to}
                  className={({ isActive }) =>
                    `inline-flex items-center gap-2 rounded-md px-3 py-1.5 text-sm ${
                      isActive ? "bg-teal-700 text-white" : "text-slate-300 hover:bg-slate-800"
                    }`
                  }
                >
                  <link.icon size={14} />
                  {link.label}
                </NavLink>
              ))}
            </nav>
          </div>
          <div className="flex items-center gap-3 text-xs text-slate-300">
            <span>
              {session?.username} · {session?.role}
            </span>
            <button
              type="button"
              onClick={logout}
              className="inline-flex items-center gap-1 rounded-md bg-slate-800 px-2 py-1 hover:bg-slate-700"
            >
              <LogOut size={12} />
              Sign out
            </button>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-7xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  );
}
