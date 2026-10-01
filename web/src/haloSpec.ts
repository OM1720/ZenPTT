// Maps connection, PTT, and audio quality state to Halo visuals and arc geometry.
import type { ClientState } from './ZenPttClient'

export type Quality = 'good' | 'fair' | 'poor'
export type HaloIcon = 'mic' | 'volume' | 'alert' | 'micOff'
export function haloSpec(state: ClientState) {
  const mode = state.status === 'error' ? 'error' : state.status === 'offline' ? 'offline'
    : state.pttError || (state.remoteTransmitting && !state.receiving) ? 'unavailable'
    : ['connecting', 'reconnecting'].includes(state.status) || ['requesting', 'ending'].includes(state.ptt) ? 'transition'
    : state.ptt === 'transmitting' ? 'transmitting' : state.receiving ? 'receiving' : 'ready'
  const rotating = mode === 'transition'
  const gap = state.quality === 'poor' ? 0.5 : state.quality === 'fair' ? 0.25 : rotating ? 0.1 : 0
  const icon: HaloIcon = mode === 'error' ? 'alert' : mode === 'unavailable' ? 'micOff'
    : mode === 'receiving' || (mode === 'transition' && state.status === 'reconnecting' && state.receiving) ? 'volume' : 'mic'
  return { mode, rotating, gap, icon, ring: mode !== 'offline' && mode !== 'error', filled: mode === 'transmitting' }
}

// Compensate rounded caps to retain the specified visible white gap in each sector.
export function haloArcs(radius: number, gap: number): string[] {
  const sweep = Math.max(0.1, 22.5 * (1 - gap) - 2 / radius * 180 / Math.PI)
  const point = (angle: number) => `${radius + 4 + radius * Math.cos(angle * Math.PI / 180)} ${radius + 4 + radius * Math.sin(angle * Math.PI / 180)}`
  return Array.from({ length: 16 }, (_, i) => {
    const middle = -90 + i * 22.5
    return `M ${point(middle - sweep / 2)} A ${radius} ${radius} 0 0 1 ${point(middle + sweep / 2)}`
  })
}

export const uplinkQuality = (age: number, horizon: number): Quality => age >= horizon * 0.2 ? 'poor' : age >= horizon * 0.1 ? 'fair' : 'good'
export const receiveQuality = (lost: number, blocked: boolean): Quality => blocked || lost > 3 ? 'poor' : lost > 0 ? 'fair' : 'good'
