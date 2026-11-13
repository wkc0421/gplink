type DeviceSnapshot = { id: string; state?: unknown }

function deviceState(device: DeviceSnapshot) {
    const state = device.state
    return typeof state === 'object' && state !== null && 'value' in state
        ? state.value
        : state
}

export function isDeviceActive(device?: DeviceSnapshot) {
    return !!device && ['offline', 'online'].includes(String(deviceState(device)))
}

/** Finish activation before the caller can bind or start a gateway. */
export async function ensureDeviceActivated<T extends DeviceSnapshot>(
    device: T,
    canActivate: boolean,
    activate: () => Promise<void>,
    read: () => Promise<T>,
): Promise<T> {
    if (!canActivate) {
        throw new Error(`设备 ${device.id} 的产品或采集器尚未启用，无法完成绑定`)
    }
    if (isDeviceActive(device)) return device
    if (deviceState(device) !== 'notActive') {
        throw new Error(`设备 ${device.id} 的启用状态未知，请重新加载后重试`)
    }
    await activate()
    const enabled = await read()
    if (enabled.id !== device.id || !isDeviceActive(enabled)) {
        throw new Error(`设备 ${device.id} 启用后状态未确认，已停止绑定`)
    }
    return enabled
}
