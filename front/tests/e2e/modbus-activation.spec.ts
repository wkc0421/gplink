import { expect, test } from '@playwright/test'
import { ensureDeviceActivated, isDeviceActive } from '../../src/modules/device-manager-ui/views/device/ModbusAccess/activation'

test('only explicit online or offline states are treated as activated', () => {
    for (const state of ['offline', 'online', { value: 'offline' }, { value: 'online' }]) {
        expect(isDeviceActive({ id: 'child', state })).toBeTruthy()
    }
    for (const state of [undefined, null, '', {}, { value: undefined }, 'notActive', 'disabled', 'unknown', 0]) {
        expect(isDeviceActive({ id: 'child', state })).toBeFalsy()
    }
    expect(isDeviceActive(undefined)).toBeFalsy()
})

test('an existing inactive child is activated and verified before binding continues', async () => {
    const calls: string[] = []
    let completeActivation!: () => void
    const pending = new Promise<void>(resolve => { completeActivation = resolve })
    const flow = ensureDeviceActivated({ id: 'existing-child', state: 'notActive' }, true,
        async () => { calls.push('activate'); await pending },
        async () => { calls.push('read'); return { id: 'existing-child', state: 'offline' } },
    ).then(device => { calls.push('bind'); return device })
    expect(calls).toEqual(['activate'])
    completeActivation()
    expect(await flow).toEqual({ id: 'existing-child', state: 'offline' })
    expect(calls).toEqual(['activate', 'read', 'bind'])
})

test('failed activation and unconfirmed state stop the binding flow', async () => {
    let bound = false
    let reads = 0
    await expect(ensureDeviceActivated({ id: 'child', state: 'notActive' }, true,
        async () => { throw new Error('activation failed') },
        async () => { reads++; return { id: 'child', state: 'offline' } },
    ).then(() => { bound = true })).rejects.toThrow('activation failed')
    expect(reads).toBe(0)
    expect(bound).toBeFalsy()

    for (const state of ['notActive', 'unknown', undefined]) {
        await expect(ensureDeviceActivated({ id: 'child', state: 'notActive' }, true,
            async () => {}, async () => ({ id: 'child', state }),
        ).then(() => { bound = true })).rejects.toThrow('启用后状态未确认')
    }
    expect(bound).toBeFalsy()
})

test('inactive prerequisites and unknown device state never proceed to activation or binding', async () => {
    let calls = 0
    const activate = async () => { calls++ }
    const read = async () => { calls++; return { id: 'child', state: 'offline' } }
    for (const state of ['notActive', 'offline']) {
        await expect(ensureDeviceActivated({ id: 'child', state }, false, activate, read))
            .rejects.toThrow('产品或采集器尚未启用')
    }
    await expect(ensureDeviceActivated({ id: 'child' }, true, activate, read)).rejects.toThrow('启用状态未知')
    expect(calls).toBe(0)
})

test('already activated devices keep their state without repeated activation', async () => {
    const device = { id: 'child', state: { value: 'offline' }, parentId: 'collector' }
    const unexpected = async () => { throw new Error('must not be called') }
    expect(await ensureDeviceActivated(device, true, unexpected, unexpected)).toBe(device)
})

test('verification cannot substitute another device', async () => {
    await expect(ensureDeviceActivated({ id: 'child', state: 'notActive' }, true,
        async () => {}, async () => ({ id: 'other-child', state: 'offline' }),
    )).rejects.toThrow('启用后状态未确认')
})
