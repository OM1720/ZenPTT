// Renders the small monochrome icon set shared by browser screens.
export type IconName = 'mic' | 'micOff' | 'volume' | 'alert' | 'power' | 'settings' | 'home' | 'download' | 'chevron' | 'ping'

// Local Lucide-compatible 24px outline geometry; no remote assets or icon runtime.
export function Icon({ name }: { name: IconName }) {
  const paths: Record<IconName, React.ReactNode> = {
    mic: <><rect x="9" y="2" width="6" height="12" rx="3" /><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3M8 22h8" /></>,
    micOff: <><path d="m2 2 20 20M9 9v3a3 3 0 0 0 5.12 2.12M15 9.34V5a3 3 0 0 0-5.94-.6M5 10v2a7 7 0 0 0 12 4.9M19 10v2c0 .5-.05 1-.16 1.48M12 19v3M8 22h8" /></>,
    volume: <><path d="m11 5-6 4H2v6h3l6 4zM15.5 8.5a5 5 0 0 1 0 7M19 5a10 10 0 0 1 0 14" /></>,
    alert: <><circle cx="12" cy="12" r="10" /><path d="M12 8v4M12 16h.01" /></>,
    power: <><circle cx="12" cy="12" r="10" /><path d="M12 7v4M7.998 9.003a5 5 0 1 0 8-.005" /></>,
    settings: <><path d="M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831 2.34 2.34 0 0 1 2.329-4.033 2.34 2.34 0 0 0 3.319-1.915" /><circle cx="12" cy="12" r="3" /></>,
    home: <><path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z" /></>,
    download: <><path d="M12 3v12m-5-5 5 5 5-5M5 17v4h14v-4" /></>,
    chevron: <path d="m6 9 6 6 6-6" />,
    ping: <path d="M2 12h4l3-8 6 16 3-8h4" />,
  }
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{paths[name]}</svg>
}
