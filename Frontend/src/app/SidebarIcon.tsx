// LE uses a 20-unit grid and a 1.4px stroke for every navigation glyph.
const glyphs = {
  overview: <><rect x="3" y="3" width="5.5" height="5.5" rx="1.3" /><rect x="11.5" y="3" width="5.5" height="8.5" rx="1.3" /><rect x="3" y="11.5" width="5.5" height="5.5" rx="1.3" /><rect x="11.5" y="14.5" width="5.5" height="2.5" rx="1" className="app-sidebar-icon-detail" /></>,
  devices: <><rect x="2.5" y="3" width="15" height="10.5" rx="1.5" /><path d="M10 13.5v3.5M6.5 17h7" /><path d="M6 10.5h8" className="app-sidebar-icon-detail" /></>,
  storage: <><rect x="3" y="2.5" width="14" height="15" rx="1.5" /><path d="M3 7.5h14M3 12.5h14" /><path d="M6 5h.01M6 10h.01M6 15h.01M12 5h2M12 10h2M12 15h2" className="app-sidebar-icon-detail" /></>,
  backup: <><path d="M5.5 14H5a3.5 3.5 0 0 1-.6-6.9A5.7 5.7 0 0 1 15.2 6a4 4 0 0 1-.2 8h-.5" /><path d="M10 17V8m-3 3 3-3 3 3" /></>,
  records: <><path d="M11.5 2.5H5a1.5 1.5 0 0 0-1.5 1.5v12A1.5 1.5 0 0 0 5 17.5h10a1.5 1.5 0 0 0 1.5-1.5V7.5Z" /><path d="M11.5 2.5V6a1.5 1.5 0 0 0 1.5 1.5h3.5" /><path d="M7 10.5h6M7 13.5h4" className="app-sidebar-icon-detail" /></>,
  restore: <><path d="M3 7a7 7 0 1 1-.2 5M2.5 3v4.5H7" /><path d="M10 6v4l3 2" className="app-sidebar-icon-detail" /></>,
  audit: <><path d="M10 2.5 17 5v5c0 3.5-3.5 6.2-7 7.5C6.5 16.2 3 13.5 3 10V5Z" /><path d="m6.5 9.5 2.3 2.3 4.7-4.5" className="app-sidebar-icon-detail" /></>,
  settings: <><path d="m8 2.5-.5 2-1.6.9L4 4.8 2.5 7.4l1.4 1.5v2.2l-1.4 1.5L4 15.2l1.9-.6 1.6.9.5 2h4l.5-2 1.6-.9 1.9.6 1.5-2.6-1.4-1.5V8.9l1.4-1.5L16 4.8l-1.9.6-1.6-.9-.5-2Z" /><circle cx="10" cy="10" r="2.5" className="app-sidebar-icon-detail" /></>,
  collapse: <><rect x="2.5" y="3.5" width="15" height="13" rx="2" /><path d="M7 3.5v13" className="app-sidebar-icon-detail" /><path d="m13.5 7.5-2.5 2.5 2.5 2.5" /></>,
  expand: <><rect x="2.5" y="3.5" width="15" height="13" rx="2" /><path d="M7 3.5v13" className="app-sidebar-icon-detail" /><path d="m11.5 7.5 2.5 2.5-2.5 2.5" /></>,
};
export function SidebarIcon({name}:{name:keyof typeof glyphs}) {
  return <span className="app-sidebar-icon" data-sidebar-icon={name} aria-hidden="true">
    <svg width="18" height="18" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" focusable="false">{glyphs[name]}</svg>
  </span>;
}
