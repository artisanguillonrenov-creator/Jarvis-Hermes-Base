import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { atom } from 'nanostores'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { SectionSync as SectionSyncType } from './section-sync'

const saveHermesConfig = vi.fn()

vi.mock('@/hermes', () => ({ saveHermesConfig }))
vi.mock('@/store/profile', () => ({
  $profiles: atom([
    { name: 'default', is_default: true },
    { name: 'research', is_default: false },
    { name: 'review', is_default: false }
  ]),
  normalizeProfileKey: (name?: string) => name?.trim() || 'default'
}))
vi.mock('@/i18n', () => ({
  useI18n: () => ({
    t: {
      settings: {
        sectionSync: {
          action: 'Sync section',
          description: 'Copy these settings to other profiles.',
          selectAll: 'All profiles',
          apply: 'Apply to selected profiles',
          cancel: 'Cancel',
          saved: (count: number) => `Synced to ${count} profiles.`,
          failed: 'Could not sync this section.',
          noTargets: 'Select at least one profile.'
        }
      }
    }
  })
}))
vi.mock('@/store/notifications', () => ({ notify: vi.fn(), notifyError: vi.fn() }))

let SectionSync: typeof SectionSyncType

beforeEach(async () => {
  vi.resetModules()
  ;({ SectionSync } = await import('./section-sync'))
  saveHermesConfig.mockReset()
})

describe('SectionSync', () => {
  it('sends only the selected section fields to every chosen target profile', async () => {
    saveHermesConfig.mockResolvedValue({ ok: true })

    render(
      <SectionSync
        fields={['agent.model', 'agent.temperature']}
        profile="default"
        source={{ agent: { model: 'Hermes-4', temperature: 0.7 }, unrelated: { keep: true } }}
      />
    )

    fireEvent.click(screen.getByRole('button', { name: 'Sync section' }))
    fireEvent.click(screen.getByLabelText('review'))
    fireEvent.click(screen.getByRole('button', { name: 'Apply to selected profiles' }))

    await waitFor(() =>
      expect(saveHermesConfig).toHaveBeenCalledWith({ agent: { model: 'Hermes-4', temperature: 0.7 } }, 'research')
    )
    expect(saveHermesConfig).toHaveBeenCalledTimes(1)
  })

  it('keeps the selector open and reports a failed target write', async () => {
    saveHermesConfig.mockResolvedValue({ ok: false })

    render(<SectionSync fields={['agent.model']} profile="default" source={{ agent: { model: 'Hermes-4' } }} />)

    fireEvent.click(screen.getByRole('button', { name: 'Sync section' }))
    fireEvent.click(screen.getByRole('button', { name: 'Apply to selected profiles' }))

    await waitFor(() => expect(saveHermesConfig).toHaveBeenCalledTimes(2))
    expect(screen.getByText('Could not sync this section.')).toBeTruthy()
  })
})
