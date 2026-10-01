import { describe, expect, it } from 'vitest'
import { ZenPttClient } from './ZenPttClient'
import { haloArcs, haloSpec, receiveQuality, uplinkQuality } from './haloSpec'

const state = new ZenPttClient('http://localhost/web/').getState()
describe('Android Halo visual contract', () => {
  it('maps offline, error, transmitting, receiving, and unavailable centers', () => {
    expect(haloSpec(state)).toMatchObject({ ring: false, icon: 'mic', filled: false })
    expect(haloSpec({ ...state, status: 'error' })).toMatchObject({ ring: false, icon: 'alert' })
    expect(haloSpec({ ...state, status: 'connected', ptt: 'transmitting' })).toMatchObject({ ring: true, filled: true, gap: 0 })
    expect(haloSpec({ ...state, status: 'connected', receiving: true })).toMatchObject({ icon: 'volume', filled: false })
    expect(haloSpec({ ...state, status: 'connected', pttError: true })).toMatchObject({ icon: 'micOff', ring: true })
  })
  it('keeps receiving center during reconnect and uses sixteen compensated sectors', () => {
    expect(haloSpec({ ...state, status: 'reconnecting', receiving: true })).toMatchObject({ rotating: true, icon: 'volume', gap: .1 })
    for (const [quality, gap] of [['fair', .25], ['poor', .5]] as const) {
      expect(haloSpec({ ...state, status: 'connected', quality }).gap).toBe(gap)
      const arc = haloArcs(186, gap)
      expect(arc).toHaveLength(16)
      expect(new Set(arc).size).toBe(16)
    }
  })
  it('uses audio loss and ACK age thresholds, independently of RTT', () => {
    expect([499, 500, 999, 1000].map(age => uplinkQuality(age, 5000))).toEqual(['good', 'fair', 'fair', 'poor'])
    expect([0, 1, 3, 4].map(lost => receiveQuality(lost, false))).toEqual(['good', 'fair', 'fair', 'poor'])
    expect(receiveQuality(0, true)).toBe('poor')
    expect(haloSpec({ ...state, status: 'connected', rttMs: 9000 }).gap).toBe(0)
  })
})
