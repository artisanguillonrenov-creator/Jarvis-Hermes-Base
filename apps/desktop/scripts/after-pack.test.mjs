import assert from 'node:assert/strict'
import { test, vi } from 'vitest'

import afterPack from './after-pack.mjs'

test('keeps the Windows package successful after exhausted identity-stamp retries', async () => {
  const stamp = vi.fn(async () => {
    throw new Error('Unable to commit changes')
  })
  const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})

  try {
    await assert.doesNotReject(
      afterPack(
        {
          electronPlatformName: 'win32',
          appOutDir: 'C:/packed',
          packager: { appInfo: { productFilename: 'Hermes' } }
        },
        { stamp }
      )
    )
    assert.equal(stamp.mock.calls.length, 1)
    assert.match(warn.mock.calls[0][0], /stock Electron icon/)
  } finally {
    warn.mockRestore()
  }
})
