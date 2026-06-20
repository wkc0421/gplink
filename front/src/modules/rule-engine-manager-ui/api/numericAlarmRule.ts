import { request } from '@jetlinks-web/core';

export type NumericOperator = 'gt' | 'gte' | 'lt' | 'lte' | 'eq' | 'neq';
export type NumericLogic = 'and' | 'or';
export type DeviceSelector = 'all' | 'fixed';
export type AlarmState = 'enabled' | 'disabled';

export interface NumericCondition {
  property?: string;
  propertyId?: string;
  propertyName?: string;
  operator?: NumericOperator;
  value?: string | number;
}

export interface NumericAlarmRule {
  id?: string;
  name: string;
  alarmConfigId: string;
  productId: string;
  selector: DeviceSelector;
  deviceIds?: string[];
  condition: {
    logic: NumericLogic;
    terms: NumericCondition[];
  };
  state?: AlarmState;
  description?: string;
}

export const queryRules = (data: Record<string, any>) =>
  request.post('/alarm/numeric-rule/_query', data);

export const saveRule = (data: NumericAlarmRule) =>
  request.post('/alarm/numeric-rule', data);

export const updateRule = (data: NumericAlarmRule) =>
  request.patch('/alarm/numeric-rule', data);

export const removeRule = (id: string) =>
  request.remove(`/alarm/numeric-rule/${id}`);

export const detailRule = (id: string) =>
  request.get(`/alarm/numeric-rule/${id}`);

export const enableRule = (id: string) =>
  request.post(`/alarm/numeric-rule/${id}/_enable`);

export const disableRule = (id: string) =>
  request.post(`/alarm/numeric-rule/${id}/_disable`);

export const queryAlarmConfigOptions = (data: Record<string, any>) =>
  request.post('/alarm/config/_query/no-paging', data);

export const queryProductOptions = (data: Record<string, any>) =>
  request.post('/device-product/_query', data);

export const queryProductDetail = (id: string) =>
  request.get(`/device-product/${id}`);

export const queryDeviceOptions = (data: Record<string, any>) =>
  request.post('/device-instance/_query', data);
