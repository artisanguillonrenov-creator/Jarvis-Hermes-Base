import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  CONFIG_SERVED_ROUTE_KEY,
  setApiRequestConnection,
  setApiRequestProfile
} from '@/hermes'
import { queryClient } from '@/lib/query-client'

import { useHermesConfigRecord } from './use-config-record'

describe('useHermesConfigRecord writeScope ownership', () => {
  let api: ReturnType<typeof vi.fn>

  beforeEach(() => {
    queryClient.clear()
    api = vi.fn(async (request: { method?: string; connectionId?: string; profile?: string }) => {
      if (request.method === 'PUT') {
        return { ok: true }
      }

      const connectionId = String(request.connectionId ?? '').trim()
      const profile = String(request.profile ?? '').trim()
      const record: Record<string, unknown> = { model: `from-${connectionId || 'local'}` }

      record[CONFIG_SERVED_ROUTE_KEY] = {
        ...(connectionId ? { connectionId } : {}),
        ...(profile ? { profile } : {})
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
    queryClient.clear()
    setApiRequestConnection(null)
    setApiRequestProfile(null)
    vi.restoreAllMocks()
    Reflect.deleteProperty(window, 'hermesDesktop')
  })

  it('same query key A→B refetch updates ownership', async () => {
    setApiRequestConnection('connection-a')
    setApiRequestProfile('default')

    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )

    const { result } = renderHook(() => useHermesConfigRecord(), { wrapper })

    await waitFor(() => expect(result.current.data?.model).toBe('from-connection-a'))
    expect(result.current.writeScope).toEqual({ connectionId: 'connection-a', profile: 'default' })

    setApiRequestConnection('connection-b')

    await act(async () => {
      await result.current.refetch()
    })

    await waitFor(() => expect(result.current.data?.model).toBe('from-connection-b'))
    expect(result.current.writeScope).toEqual({ connectionId: 'connection-b', profile: 'default' })
  })
})
