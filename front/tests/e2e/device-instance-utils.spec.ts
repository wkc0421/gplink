import { expect, test } from '@playwright/test'
import {
    getDeviceActionErrorMessage,
    isDeviceActionSuccess,
} from '../../src/modules/device-manager-ui/views/device/Instance/utils'

test.describe('device instance action response helpers', () => {
    test('accepts a successful request and business result', () => {
        expect(isDeviceActionSuccess({
            status: 200,
            success: true,
            result: { success: true },
        })).toBe(true)
    })

    test('rejects an HTTP 200 response when the business result failed', () => {
        expect(isDeviceActionSuccess({
            status: 200,
            success: true,
            result: { success: false, message: 'deploy failed' },
        })).toBe(false)
    })

    test('uses the business error message before the generic fallback', () => {
        expect(getDeviceActionErrorMessage({
            result: { success: false, message: 'deploy failed' },
        }, 'operation failed')).toBe('deploy failed')
    })
})
