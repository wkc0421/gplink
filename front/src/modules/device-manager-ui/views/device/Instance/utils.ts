export function isDeviceActionSuccess(response: any): boolean {
    const requestSucceeded =
        response?.success === true || response?.status === 200;

    return requestSucceeded && response?.result?.success !== false;
}

export function getDeviceActionErrorMessage(
    response: any,
    fallback: string,
): string {
    return response?.result?.message || response?.message || fallback;
}
