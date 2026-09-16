import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  CONFIG_SERVED_ROUTE_KEY,
  getHermesConfigRecord,
  peekConfigReadOrigin,
  resolveConfigWriteScope,
  saveHermesConfig,
  saveHermesConfigRecord,
  setApiRequestConnection,
  setApiRequestProfile
} from '@/hermes'

describe('config read/write route binding', () => {
  let api: ReturnType<typeof vi.fn>

  beforeEach(() => {
    api = vi.fn(async (request: { method?: string; connectionId?: string; profile?: string }) => {
      if (request.method === 'PUT') {
        return { ok: true }
      }

      const record: Record<string, unknown> = { model: 'from-read' }
      const connectionId = String(request.connectionId ?? '').trim()
      const profile = String(request.profile ?? '').trim()

      // Mirror Electron: stamp the route that actually served the GET.
      if (connectionId || profile) {
        record[CONFIG_SERVED_ROUTE_KEY] = {
          ...(connectionId ? { connectionId } : {}),
          ...(profile ? { profile } : {})
        }
      }

      return record
    })
    Object.defineProperty(window, 'hermesDesktop', {
      configurable: true,
      value: { api }
    })
    setApiRequestConnection(null)
    setApiRequestProfile(null)
  })

  afterEach(() => {
    setApiRequestConnection(null)
    setApiRequestProfile(null)
    vi.restoreAllMocks()
    Reflect.deleteProperty(window, 'hermesDesktop')
  })

  it('config record read from A cannot be written to B after primary changes', async () => {
    setApiRequestConnection('connection-a')
    setApiRequestProfile('default')

    const record = await getHermesConfigRecord()

    expect(peekConfigReadOrigin(record)).toEqual({ connectionId: 'connection-a', profile: 'default' })
    expect(record).not.toHaveProperty(CONFIG_SERVED_ROUTE_KEY)
    expect(api).toHaveBeenCalledWith(
      expect.objectContaining({ connectionId: 'connection-a', path: '/api/config', profile: 'default' })
    )

    setApiRequestConnection('connection-b')
    await saveHermesConfig(record)

    const puts = api.mock.calls.filter(call => call[0].method === 'PUT')

    expect(puts).toHaveLength(1)
    expect(puts[0][0]).toEqual(
      expect.objectContaining({
        connectionId: 'connection-a',
        method: 'PUT',
        path: '/api/config',
        profile: 'default'
      })
    )
    expect(puts.filter(call => call[0].connectionId === 'connection-b')).toHaveLength(0)
  })

  it('binds provenance from the Electron-served route, not ambient request scope', async () => {
    // Ambient request carries no connectionId; Electron still served primary A.
    api.mockImplementationOnce(async () => {
      const record: Record<string, unknown> = { model: 'from-a' }
      record[CONFIG_SERVED_ROUTE_KEY] = { connectionId: 'connection-a', profile: 'default' }

      return record
    })

    const record = await getHermesConfigRecord()

    expect(peekConfigReadOrigin(record)).toEqual({ connectionId: 'connection-a', profile: 'default' })
    expect(record).not.toHaveProperty(CONFIG_SERVED_ROUTE_KEY)
    expect(api.mock.calls[0][0].connectionId).toBeUndefined()
  })

  it('GET served by A then primary→local keeps PUT on A (not B/local)', async () => {
    api.mockImplementationOnce(async () => {
      const record: Record<string, unknown> = { model: 'from-a' }
      record[CONFIG_SERVED_ROUTE_KEY] = { connectionId: 'connection-a', profile: 'default' }

      return record
    })

    const record = await getHermesConfigRecord()

    // Primary handoff: ambient becomes local / empty — must not retarget.
    setApiRequestConnection(null)
    await saveHermesConfig(record)

    const put = api.mock.calls.find(call => call[0].method === 'PUT')?.[0]

    expect(put).toEqual(
      expect.objectContaining({
        connectionId: 'connection-a',
        method: 'PUT',
        path: '/api/config',
        profile: 'default'
      })
    )
    expect(put.connectionId).not.toBe('connection-b')
    expect(put.connectionId).not.toBe('local')
  })

  it('explicit connection/profile pins still win over a captured origin', async () => {
    setApiRequestConnection('connection-a')
    const record = await getHermesConfigRecord()
    setApiRequestConnection('connection-b')

    await saveHermesConfigRecord(record, { connectionId: 'explicit-pin', profile: 'worker' })

    const put = api.mock.calls.find(call => call[0].method === 'PUT')?.[0]

    expect(put).toEqual(
      expect.objectContaining({
        connectionId: 'explicit-pin',
        profile: 'worker'
      })
    )
  })

  it('unbound local writes keep the live ambient path', () => {
    setApiRequestConnection('connection-b')
    setApiRequestProfile('coder')

    expect(resolveConfigWriteScope({ model: 'fresh' })).toEqual({
      connectionId: 'connection-b',
      profile: 'coder'
    })
  })
})
