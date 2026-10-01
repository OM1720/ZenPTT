import { afterEach, describe, expect, it } from 'vitest'
import { ZenPttClient } from '../src/ZenPttClient'

const pageUrl = process.env.ZENPTT_WEB_URL ?? 'http://127.0.0.1:5173/web/'
const clients: ZenPttClient[] = []
function create() {
  const client = new ZenPttClient(pageUrl)
  clients.push(client)
  return client
}
function channel() { return `WEB.${crypto.randomUUID().replaceAll('-', '').toUpperCase()}` }
afterEach(async () => { await Promise.all(clients.splice(0).map(client => client.disconnect())) })

describe('real server through the same-origin development proxy', () => {
  it('joins two clients, maintains presence, leaves and rejoins without duplicate membership', async () => {
    const first = create()
    const second = create()
    const room = channel()
    first.connect(room)
    second.connect(room)
    await expect.poll(() => [first.getState().participantCount, second.getState().participantCount]).toEqual([2n, 2n])
    first.connect(room)
    // Server presence lasts three seconds; keeping two participants proves application pings work.
    await new Promise(resolve => setTimeout(resolve, 3500))
    expect([first.getState().participantCount, second.getState().participantCount]).toEqual([2n, 2n])
    await second.disconnect()
    await expect.poll(() => first.getState().participantCount).toBe(1n)
    second.connect(room)
    await expect.poll(() => [first.getState().participantCount, second.getState().participantCount]).toEqual([2n, 2n])
  })
  it('switches membership to another channel and returns', async () => {
    const first = create()
    const second = create()
    const room = channel()
    first.connect(room)
    second.connect(room)
    await expect.poll(() => first.getState().participantCount).toBe(2n)
    second.connect(channel())
    await expect.poll(() => [first.getState().participantCount, second.getState().participantCount]).toEqual([1n, 1n])
    second.connect(room)
    await expect.poll(() => first.getState().participantCount).toBe(2n)
  })
  it('joins a private ECHO session with the existing supervisor', async () => {
    const client = create()
    client.connect('ECHO')
    await expect.poll(() => client.getState(), { timeout: 10000 }).toMatchObject({
      status: 'connected', channel: 'ECHO', participantCount: 2n,
    })
  })
})
