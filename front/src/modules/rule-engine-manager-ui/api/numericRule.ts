import { request } from '@jetlinks-web/core';

export type NumericRuleTerm = {
  column: string;
  termType: string;
  value: number | number[];
};

export type NumericRulePayload = {
  name: string;
  productId: string;
  deviceIds: string;
  termList: NumericRuleTerm[];
  type: 'and' | 'or';
  shakeLimit: {
    enabled: boolean;
    time: number;
    threshold: number;
    alarmFirst: boolean;
  };
};

export const createNumericRule = (data: NumericRulePayload) =>
  request.post('/v1/rule/scene/alarm/_trigger', data);

export const updateNumericRule = (id: string, data: NumericRulePayload) =>
  request.put(`/v1/rule/scene/${id}/alarm/_trigger`, data);

export const getNumericRuleDetail = (id: string) => request.get(`/scene/${id}`);

export const parseNumericRuleTerms = (data: Record<string, any>) =>
  request.post('/scene/parse-term-column', data);

const hasAlarmMode = (scene: any, mode: 'trigger' | 'relieve') =>
  (scene?.branches || []).some((branch: any) =>
    (branch?.then || []).some((thenItem: any) =>
      (thenItem?.actions || []).some(
        (action: any) => action?.executor === 'alarm' && action?.alarm?.mode === mode,
      ),
    ),
  );

/**
 * Numeric rules are stored as SceneEntity records. The legacy alarm endpoint
 * does not set SceneEntity.features, so features cannot be used as the only
 * discriminator here.
 */
export const isNumericAlarmRule = (scene: any) =>
  scene?.triggerType === 'device' &&
  scene?.trigger?.device?.operation?.operator === 'reportProperty' &&
  hasAlarmMode(scene, 'trigger') &&
  hasAlarmMode(scene, 'relieve');

const getPage = (data: any[], pageIndex: number, pageSize: number) => {
  const start = Math.max(pageIndex, 0) * pageSize;
  return data.slice(start, start + pageSize);
};

/** Query all device scenes, identify legacy-compatible numeric rules, then page locally. */
export const queryNumericRules = async (data: Record<string, any> = {}) => {
  const response: any = await request.post('/scene/_query/no-paging', {
    ...data,
    paging: false,
    terms: [
      { column: 'triggerType', termType: 'eq', value: 'device' },
      ...(data.terms || []),
    ],
  });

  if (!response?.success) {
    return response;
  }

  const source = Array.isArray(response.result)
    ? response.result
    : response.result?.data || [];
  const filtered = source.filter(isNumericAlarmRule);
  const pageIndex = Number(data.pageIndex || 0);
  const pageSize = Math.max(Number(data.pageSize || 10), 1);
  const result = Array.isArray(response.result) ? {} : response.result || {};

  return {
    ...response,
    result: {
      ...result,
      data: getPage(filtered, pageIndex, pageSize),
      total: filtered.length,
      pageIndex,
      pageSize,
    },
  };
};
