import { expect, it, vi } from 'vitest'
import { PttHold } from './PttHold'

it('combines mouse and keyboard without repeated requests or early release', () => {
  const down = vi.fn(() => true), up = vi.fn()
  const hold = new PttHold(down, up)
  hold.press('pointer:1')
  hold.press('keyboard')
  hold.press('keyboard')
  hold.release('pointer:1')
  expect(down).toHaveBeenCalledOnce()
  expect(up).not.toHaveBeenCalled()
  hold.release('keyboard')
  expect(up).toHaveBeenCalledOnce()
})
it('cancels every source and never restarts when the page returns', () => {
  const down = vi.fn(() => true), up = vi.fn()
  const hold = new PttHold(down, up)
  hold.press('keyboard')
  hold.cancel()
  hold.release('keyboard')
  expect(up).toHaveBeenCalledOnce()
  expect(down).toHaveBeenCalledOnce()
  hold.press('keyboard')
  expect(down).toHaveBeenCalledTimes(2)
})
