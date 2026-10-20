import type { MoneyFen } from '../contracts';
import type { DistributorSummary, ProductDistributorRule } from './types';
import { parseYuan } from '../money';

export interface WithdrawalValidationResult {
  valid: boolean;
  amountFen: MoneyFen;
  error?: string;
}

export function validateWithdrawalAmount(
  inputYuan: string,
  summary: DistributorSummary,
): WithdrawalValidationResult {
  if (summary.status === 'RISK_BLOCKED') {
    return {
      valid: false,
      amountFen: 0,
      error: '账户已被风险冻结，禁止提现',
    };
  }

  if (summary.status === 'DISABLED') {
    return {
      valid: false,
      amountFen: 0,
      error: '分销档案已停用',
    };
  }

  if (!summary.withdrawalEnabled) {
    return {
      valid: false,
      amountFen: 0,
      error: summary.disabledReason || '当前账户暂不支持提现',
    };
  }

  if (summary.debtFen > 0) {
    return {
      valid: false,
      amountFen: 0,
      error: `当前存在售后退款欠款 ¥${(summary.debtFen / 100).toFixed(2)}，暂不可提现`,
    };
  }

  let amountFen: MoneyFen;
  try {
    amountFen = parseYuan(inputYuan);
  } catch (e) {
    return {
      valid: false,
      amountFen: 0,
      error: e instanceof Error ? e.message : '提现金额格式不正确',
    };
  }

  if (amountFen <= 0) {
    return { valid: false, amountFen: 0, error: '提现金额必须大于 0' };
  }

  const minLimit = summary.limits?.minFen ?? summary.limits?.minAmountFen ?? 100;
  const maxLimit = summary.limits?.maxFen ?? summary.limits?.maxAmountFen ?? Infinity;

  if (amountFen < minLimit) {
    return {
      valid: false,
      amountFen,
      error: `单笔最低提现金额为 ¥${(minLimit / 100).toFixed(2)}`,
    };
  }

  if (amountFen > maxLimit) {
    return {
      valid: false,
      amountFen,
      error: `单笔最高提现金额为 ¥${(maxLimit / 100).toFixed(2)}`,
    };
  }

  if (amountFen > summary.availableFen) {
    return {
      valid: false,
      amountFen,
      error: `提现金额超出可用余额（当前可用 ¥${(summary.availableFen / 100).toFixed(2)}）`,
    };
  }

  if (summary.limits.dailyCountRemaining !== undefined && summary.limits.dailyCountRemaining <= 0) {
    return {
      valid: false,
      amountFen,
      error: '今日提现次数已达上限，请明日再试',
    };
  }

  return { valid: true, amountFen };
}

export function validateDistributionRules(rules: ProductDistributorRule[]): string | null {
  const seen = new Set<string>();
  for (const rule of rules) {
    if (!rule.distributorId) return '分销员ID不能为空';
    if (seen.has(rule.distributorId))
      return `分销员 ${rule.distributorName || rule.distributorId} 重复配置`;
    seen.add(rule.distributorId);

    if (rule.rateBps < 0 || rule.rateBps > 10000) {
      return '佣金比例必须在 0% ~ 100% 之间';
    }
  }
  return null;
}
