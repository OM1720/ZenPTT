import { expect, it } from 'vitest'
import { loadPreferences, rememberChannel, savePreferences } from './preferences'

it('validates stored preferences and bounds unique history with ECHO last', () => {
  const value = loadPreferences({ getItem: () => JSON.stringify({ channel: 'room1', history: ['A', 'A', '..', 'B', 'C'], microphone: 'device' }) })
  expect(value).toEqual({ channel: 'ROOM1', history: ['A', 'B', 'C', 'ECHO'], microphone: 'device' })
  expect(rememberChannel({ ...value, history: ['A', 'B', 'C', 'D', 'ECHO'] }, 'NEW').history).toEqual(['NEW', 'A', 'B', 'C', 'ECHO'])
})
it('survives denied storage, malformed JSON, and oversized content', () => {
  for (const getItem of [() => '{', () => 'x'.repeat(9000), () => { throw new Error('blocked') }]) {
    expect(loadPreferences({ getItem })).toEqual({ channel: '', history: ['ECHO'], microphone: '' })
  }
  expect(savePreferences(loadPreferences({ getItem: () => null }), { setItem: () => { throw new Error('full') } })).toBe(false)
})
