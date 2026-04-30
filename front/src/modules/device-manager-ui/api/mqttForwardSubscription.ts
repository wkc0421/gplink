import { request } from '@jetlinks-web/core';

export interface MqttForwardLease {
    leaseId: string;
    productId: string;
    mqttNetworkId: string;
    mqttTopicPrefix?: string;
    mqttQos: number;
    createdAt: number;
    expiresAt: number;
    ttlSeconds: number;
    deviceIds: string[];
    watchedProperties: string[];
    forwardCount: number;
    lastForwardTime: number;
    lastForwardDeviceId?: string;
    lastForwardProperties: string[];
}

export interface MqttForwardActive {
    ttlSeconds: number;
    leaseCount: number;
    deviceSubscriptionCount: number;
    indexDeviceCount: number;
    indexEntryCount: number;
    indexLeaseRefCount: number;
    forwardedLeaseCount: number;
    totalForwardCount: number;
    lastForwardTime: number;
    leases: MqttForwardLease[];
    deviceSubscriptions: Record<string, boolean>;
}

export interface CreateMqttForwardLeaseRequest {
    productId: string;
    deviceIds: string[];
    mqttNetworkId: string;
    mqttTopicPrefix?: string;
    mqttQos: number;
    watchedProperties?: string;
}

const basePath = '/v1/mqtt/forward/subscription';

export const queryActiveLeases = () => request.get<MqttForwardActive>(`${basePath}/active`);

export const createLeaseByDevices = (data: CreateMqttForwardLeaseRequest) =>
    request.post<MqttForwardLease>(`${basePath}/by-devices`, data);

export const renewLease = (leaseId: string) =>
    request.put<MqttForwardLease>(`${basePath}/${leaseId}/renew`);

export const closeLease = (leaseId: string) => request.remove(`${basePath}/${leaseId}`);

export const queryMqttClientNetworks = () =>
    request.get('/network/config/MQTT_CLIENT/_alive?include=true');
