// Validates and persists the browser's channel draft, history, and microphone choice.
import { normalizeChannel } from './protocol'

export const PREFERENCES_KEY = 'zenptt.web.preferences.v1'
export interface Preferences { channel: string; history: string[]; microphone: string }
const defaults = (): Preferences => ({ channel: '', history: ['ECHO'], microphone: '' })

export function loadPreferences(storage?: Pick<Storage, 'getItem'>): Preferences {
  try {
    const raw = (storage ?? localStorage).getItem(PREFERENCES_KEY)
    if (!raw || raw.length > 8192) return defaults()
    const value = JSON.parse(raw) as Partial<Preferences>
    const history: string[] = []
    if (Array.isArray(value.history)) for (const item of value.history.slice(0, 5)) {
      try { const channel = normalizeChannel(item); if (channel !== 'ECHO' && !history.includes(channel)) history.push(channel) } catch { /* Ignore invalid saved entries. */ }
    }
    let channel = ''
    try { channel = normalizeChannel(value.channel ?? '') } catch { /* Keep an empty draft. */ }
    return { channel, history: [...history.slice(0, 4), 'ECHO'],
      microphone: typeof value.microphone === 'string' && value.microphone.length <= 512 ? value.microphone : '' }
  } catch { return defaults() }
}

export function savePreferences(value: Preferences, storage?: Pick<Storage, 'setItem'>): boolean {
  try { (storage ?? localStorage).setItem(PREFERENCES_KEY, JSON.stringify(value)); return true } catch { return false }
}

export function rememberChannel(value: Preferences, channel: string): Preferences {
  return { ...value, channel, history: [
    ...[channel, ...value.history].filter((item, index, all) => item !== 'ECHO' && all.indexOf(item) === index).slice(0, 4), 'ECHO',
  ] }
}
