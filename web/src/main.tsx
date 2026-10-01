// Creates the page-wide client and mounts the React UI with page-exit cleanup.
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { ZenPttClient } from './ZenPttClient'
import './styles.css'
import { loadPreferences } from './preferences'

// The page owns one client; React renders and screen changes never own its lifetime.
const client = new ZenPttClient(window.location.href)
void client.selectMicrophone(loadPreferences().microphone)
const leave = () => { void client.disconnect() }
window.addEventListener('pagehide', leave)
if (import.meta.hot) {
  import.meta.hot.dispose(() => {
    window.removeEventListener('pagehide', leave)
    void client.disconnect()
  })
}
createRoot(document.getElementById('root')!).render(<StrictMode><App client={client} /></StrictMode>)
