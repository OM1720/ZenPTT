// Owns the browser screens, channel draft, navigation, and physical PTT inputs.
import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import { isTyping, PttHold } from './PttHold'
import { normalizeChannel } from './protocol'
import { loadPreferences, rememberChannel, savePreferences } from './preferences'
import { Halo } from './Halo'
import { Icon } from './Icons'
import { Settings } from './Settings'
import { AndroidDownload } from './AndroidDownload'
import type { ZenPttClient } from './ZenPttClient'

export function App({ client }: { client: ZenPttClient }) {
  const state = useSyncExternalStore(client.subscribe, client.getState)
  const [preferences, setPreferences] = useState(loadPreferences)
  const [draft, setDraft] = useState(preferences.channel)
  const [screen, setScreen] = useState<'main' | 'settings'>('main')
  const [historyOpen, setHistoryOpen] = useState(false)
  const [inputError, setInputError] = useState<string | null>(null)
  const [storageError, setStorageError] = useState(false)
  const field = useRef<HTMLInputElement>(null)
  const home = useRef<HTMLButtonElement>(null)
  const settingsButton = useRef<HTMLButtonElement>(null)
  const hold = useMemo(() => new PttHold(() => client.pttDown(), () => client.pttUp()), [client])
  useEffect(() => {
    const release = () => hold.cancel()
    const visibility = () => { if (document.hidden) release() }
    const down = (event: KeyboardEvent) => {
      if (event.code !== 'Space' || event.altKey || event.ctrlKey || event.metaKey
        || isTyping(event.target) || document.hidden) return
      // Space is reserved for PTT outside text fields, including focused navigation buttons.
      event.preventDefault()
      if (screen !== 'main' || event.repeat) return
      hold.press('keyboard')
    }
    const up = (event: KeyboardEvent) => {
      if (event.code !== 'Space') return
      if (!isTyping(event.target)) event.preventDefault()
      hold.release('keyboard')
    }
    window.addEventListener('blur', release)
    window.addEventListener('keydown', down)
    window.addEventListener('keyup', up)
    document.addEventListener('visibilitychange', visibility)
    return () => {
      release()
      window.removeEventListener('blur', release)
      window.removeEventListener('keydown', down)
      window.removeEventListener('keyup', up)
      document.removeEventListener('visibilitychange', visibility)
    }
  }, [hold, screen])
  useEffect(() => { if (screen === 'settings') home.current?.focus() }, [screen])
  useEffect(() => { if (state.status !== 'connected') hold.release('accessibility') }, [hold, state.status])
  const active = ['connected', 'connecting', 'reconnecting'].includes(state.status)
  const count = state.status === 'connected' && draft.toUpperCase() === state.channel
    ? String(state.channel === 'ECHO' ? 1 : state.participantCount) : '—'
  const statusLabel = { offline: 'Offline', connecting: 'Connecting…', connected: 'Connected', reconnecting: 'Reconnecting…', error: 'Connection error' }[state.status]
  const talkLabel = { idle: 'Push to talk', requesting: 'Requesting…', transmitting: 'Transmitting', ending: 'Finishing…' }[state.ptt]

  function connect(candidate = draft) {
    try {
      const channel = normalizeChannel(candidate)
      const updated = rememberChannel(preferences, channel)
      setDraft(channel)
      setPreferences(updated)
      setStorageError(!savePreferences(updated))
      setInputError(null)
      setHistoryOpen(false)
      hold.cancel()
      client.connect(channel)
      void client.enableAudio()
      field.current?.blur()
    } catch (error) {
      setInputError(error instanceof Error ? error.message : 'Invalid channel')
      setHistoryOpen(true)
      field.current?.focus()
    }
  }

  function openSettings() { hold.cancel(); setHistoryOpen(false); setScreen('settings') }
  function closeSettings() { setScreen('main'); requestAnimationFrame(() => settingsButton.current?.focus()) }

  return <main className={`app screen-${screen}`}>
    {screen === 'settings' ? <Settings client={client} preferences={preferences} homeRef={home} onHome={closeSettings}
      onPreferences={value => { setPreferences(value); setStorageError(!savePreferences(value)) }} /> : <>
      <header className="topbar">
        <button className={`icon-button connection ${active ? state.status === 'connected' ? 'connected' : 'pending' : 'off'}`}
          aria-label={active ? 'Disconnect' : 'Connect'} title={active ? 'Disconnect from the current channel.' : 'Connect to the channel entered below.'} onClick={() => {
            if (active) { hold.cancel(); void client.disconnect() } else connect()
          }}><Icon name="power" /></button>
        <h1 tabIndex={-1}>ZenPTT</h1>
        <button ref={settingsButton} className="icon-button" aria-label="Settings" title="Open Settings. Incoming audio keeps playing." onClick={openSettings}><Icon name="settings" /></button>
      </header>
      <div className="main-content">
        <section className="frequency" aria-label="Channel connection">
          <form onSubmit={event => { event.preventDefault(); connect() }} noValidate>
            <div className="channel-row">
              <div className="field-history" onBlur={event => {
                if (!event.currentTarget.contains(event.relatedTarget)) setHistoryOpen(false)
              }}>
                <div className="field-outline">
                  <input ref={field} aria-label="Channel" title="Enter a channel code and press Enter to connect or switch. Use ECHO to test your audio." value={draft} onChange={event => { setDraft(event.target.value.toUpperCase()); setInputError(null) }}
                    onFocus={() => setHistoryOpen(true)} onKeyDown={event => {
                      if (event.key === 'Escape') { setHistoryOpen(false); event.stopPropagation() }
                      if (event.key === 'ArrowDown') { event.preventDefault(); setHistoryOpen(true); requestAnimationFrame(() => document.querySelector<HTMLButtonElement>('.history button')?.focus()) }
                    }} autoComplete="off" spellCheck={false} placeholder="ROOM1" aria-invalid={inputError !== null}
                    aria-describedby={inputError ? 'channel-error' : undefined} aria-controls="channel-history" aria-expanded={historyOpen} />
                  <button type="button" className="history-toggle icon-button" aria-label="Channel history" title="Show recent channels." aria-expanded={historyOpen}
                    onClick={() => setHistoryOpen(!historyOpen)}><Icon name="chevron" /></button>
                </div>
                {historyOpen && <div id="channel-history" className="history" aria-label="Recent channels" onKeyDown={event => {
                  if (event.key === 'Escape') { field.current?.focus(); setHistoryOpen(false) }
                }}>{preferences.history.map(channel => <button type="button" key={channel} title={`Connect to ${channel}.`} onClick={() => connect(channel)}>{channel}</button>)}</div>}
              </div>
              <div className="participants" role="status" aria-label="Participants" title="Connected participants in the selected channel.">{count}</div>
            </div>
            {inputError && <p id="channel-error" className="error" role="alert">{inputError}</p>}
          </form>
          <div className="connection-state sr-only" role="status" aria-label="Connection status">{statusLabel}{active && state.channel ? ` · ${state.channel}` : ''}</div>
          {state.error && <p className="error" role="alert">{state.error}</p>}
          {state.audio === 'preparing' && <p className="hint" role="status">Preparing audio… Allow microphone access if prompted.</p>}
          {state.audioError && <div>
            <p className="error" role="alert">{state.audioError}</p>
            <button className="button" title="Try preparing microphone and playback again." onClick={() => { hold.cancel(); void client.enableAudio() }}>Retry audio</button>
          </div>}
        </section>
        <section className="talk-area" aria-label="Push to talk">
          <button className="ptt" disabled={!state.pttAvailable} aria-describedby="audio-notice" aria-label="Push to talk"
            title="Hold the Halo or Space to talk. Release to listen."
            aria-pressed={state.ptt === 'transmitting'} onPointerDown={event => {
              if (event.button !== 0) return
              event.preventDefault()
              event.currentTarget.setPointerCapture(event.pointerId)
              hold.press(`pointer:${event.pointerId}`)
            }} onPointerUp={event => hold.release(`pointer:${event.pointerId}`)} onPointerCancel={() => hold.cancel()}
            onLostPointerCapture={event => hold.release(`pointer:${event.pointerId}`)} onContextMenu={event => event.preventDefault()}
            onClick={event => { if (event.detail === 0) { if (state.ptt === 'idle') hold.press('accessibility'); else hold.cancel() } }}>
            <Halo state={state} /><span className="sr-only">{talkLabel}</span>
          </button>
          <p id="audio-notice" className="sr-only" role="status">{state.receiving ? 'Receiving audio. ' : ''}{statusLabel}. {state.quality} audio quality.
            {` RTT ${state.rttMs ?? 'unknown'} ms; PTT grant ${state.grantMs ?? 'unknown'} ms; ${state.lostFrames} lost frames.`}
            {state.playbackBlocked ? ' Playback is blocked.' : ''} Hold Space or the button to talk. Screen reader click toggles PTT.</p>
        </section>
      </div>
      <footer className="main-footer"><AndroidDownload /></footer>
    </>}
    {storageError && <p className="error" role="alert">Browser storage is unavailable. Settings will last for this page only.</p>}
  </main>
}
