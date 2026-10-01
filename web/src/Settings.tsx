// Renders browser settings, microphone selection, ping, and local diagnostics export.
import { useEffect, useState } from 'react'
import type { RefObject } from 'react'
import type { ZenPttClient } from './ZenPttClient'
import type { Preferences } from './preferences'
import { Icon } from './Icons'

export function Settings({ client, preferences, onPreferences, onHome, homeRef }: {
  client: ZenPttClient; preferences: Preferences; onPreferences: (value: Preferences) => void;
  onHome: () => void; homeRef: RefObject<HTMLButtonElement | null>;
}) {
  const [devices, setDevices] = useState<MediaDeviceInfo[]>([])
  const [busy, setBusy] = useState(false)
  const [ping, setPing] = useState('')
  const [pingBusy, setPingBusy] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => {
    let active = true
    const refresh = () => { void navigator.mediaDevices.enumerateDevices().then(list => {
      if (active) setDevices(list.filter(device => device.kind === 'audioinput' && device.deviceId && device.deviceId !== 'default'))
    }).catch(() => { if (active) setError('Microphone list is unavailable. Connect on Main and try again.') }) }
    refresh()
    navigator.mediaDevices.addEventListener('devicechange', refresh)
    return () => { active = false; navigator.mediaDevices.removeEventListener('devicechange', refresh) }
  }, [])
  async function selectMicrophone(deviceId: string) {
    setBusy(true); setError('')
    try { await client.selectMicrophone(deviceId); onPreferences({ ...preferences, microphone: deviceId }) }
    catch (failure) { setError(failure instanceof Error ? failure.message : 'Microphone could not be changed.') }
    finally { setBusy(false) }
  }
  function download() {
    const report = client.diagnosticReport(import.meta.env.VITE_WEB_BUILD)
    const url = URL.createObjectURL(new Blob([report], { type: 'application/json' }))
    const link = document.createElement('a')
    link.href = url; link.download = 'zenptt-web-diagnostics.json'; link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  return <>
    <header className="topbar"><h1>Settings</h1><button ref={homeRef} className="icon-button" aria-label="Home" title="Return to Main. Your session stays connected." disabled={busy} onClick={onHome}><Icon name="home" /></button></header>
    <div className="settings-content">
      <section className="settings-row server-row">
        <label htmlFor="server">Server</label>
        <div className="setting-action"><input id="server" readOnly title="The server that hosts this page. This address cannot be changed here." value={location.origin.replace(/^http/, 'ws') + '/ws'} />
          <button className="button" title="Check the server connection and measure round-trip time." disabled={pingBusy} onClick={() => {
            setPingBusy(true); setPing('')
            void client.ping().then(ms => setPing(`Ping: ${ms} ms`), () => setPing('Ping failed. Check the server connection.')).finally(() => setPingBusy(false))
          }}><Icon name="ping" />{pingBusy ? 'Pinging…' : 'Ping'}</button></div>
        <p className="hint">Uses the server that hosts this page.</p><p role="status" aria-label="Ping result">{ping}</p>
      </section>
      <section className="settings-row" aria-labelledby="audio-heading">
        <h2 id="audio-heading">Audio</h2>
        <label htmlFor="microphone">Microphone</label>
        <select id="microphone" title="Choose the microphone used for transmission. Incoming audio continues during a change." value={preferences.microphone} disabled={busy} onChange={event => { void selectMicrophone(event.target.value) }}>
          <option value="">System default</option>
          {preferences.microphone && !devices.some(device => device.deviceId === preferences.microphone) && <option value={preferences.microphone}>Saved microphone (unavailable)</option>}
          {devices.map((device, i) => <option key={device.deviceId} value={device.deviceId}>{device.label || `Microphone ${i + 1}`}</option>)}
        </select><p className="hint">Connect on Main and allow microphone access to start audio and show device names.</p>
        <h3>Audio output</h3><p title="Playback uses the output device selected in Windows.">System default device</p><p className="hint">Change the output in Windows sound settings.</p>
      </section>
      <section className="settings-row"><h2>Diagnostics</h2><button className="button" title="Save a local diagnostic report without audio, tokens, or channel codes." onClick={download}><Icon name="download" />Download diagnostic report</button>
        <p className="hint">A bounded local report. No audio, tokens, microphone names, or channel codes.</p></section>
    </div>
    {error && <p role="alert" className="error">{error}</p>}
    <footer className="settings-footer" title="Version of the web client currently running.">
      <p className="build-version">Web build · {import.meta.env.VITE_WEB_BUILD}</p>
      <p>ZenPTT web · Protocol v4</p>
    </footer>
  </>
}
