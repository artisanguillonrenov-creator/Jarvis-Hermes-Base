import { AssistantRuntimeProvider, type ThreadMessage, useExternalStoreRuntime } from '@assistant-ui/react'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import { $reactionsEnabled } from '@/store/reactions-enabled'

import { assistantMessage, stubThreadEnvironment } from '../test-utils'

import { Thread } from '.'

stubThreadEnvironment()

function Harness() {
  const runtime = useExternalStoreRuntime<ThreadMessage>({
    messages: [assistantMessage()],
    isRunning: false,
    onNew: async () => {}
  })

  return (
    <AssistantRuntimeProvider runtime={runtime}>
      <Thread />
    </AssistantRuntimeProvider>
  )
}

beforeEach(() => {
  $reactionsEnabled.set(true)
})

afterEach(() => {
  cleanup()
  $reactionsEnabled.set(false)
})

describe('assistant reaction picker', () => {
  it('keeps the footer interactive after leaving the message row for the portaled picker', async () => {
    render(<Harness />)

    const message = (await screen.findByText('done')).closest('[data-slot="aui_assistant-message-root"]')
    const trigger = message?.querySelector<HTMLButtonElement>('[data-slot="aui_msg-reactions"]')

    expect(trigger).toBeTruthy()
    fireEvent.click(trigger!)
    fireEvent.mouseLeave(message!)

    expect(message?.querySelector('[data-slot="aui_msg-actions"]')?.getAttribute('data-reaction-picker-open')).toBe(
      'true'
    )
  })
})
