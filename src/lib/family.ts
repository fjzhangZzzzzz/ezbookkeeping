import axios from 'axios';

import { parseBigDecimal } from '@/lib/numeral.ts';

export interface FamilyAmounts {
    incomeMinor: string;
    expenseMinor: string;
    netMinor: string;
}

export interface FamilyMember extends FamilyAmounts {
    id: string;
    nickname: string;
}

export interface FamilyCategory {
    id: string;
    name: string;
    type: number;
    icon: string;
    amountMinor: string;
    previousAmountMinor: string;
    parent?: { id: string; name: string; icon: string };
}

export interface FamilyCategoryRow {
    id: string;
    name: string;
    icon: string;
    amount: bigint;
    previous: bigint;
}

export interface FamilyCategoryGroup extends FamilyCategoryRow {
    children: FamilyCategoryRow[];
}

export interface FamilyAccount {
    id: string;
    ownerId: string;
    ownerName: string;
    name: string;
    currency: string;
    balanceMinor: string;
    hidden: boolean;
}

export interface FamilyReport extends FamilyAmounts {
    averageIncomeMinor: string;
    averageExpenseMinor: string;
    averageNetMinor: string;
    days: (FamilyAmounts & { date: string })[];
    months: (FamilyAmounts & { month: string })[];
    members: FamilyMember[];
    categories: FamilyCategory[];
    priorOnlyCategories?: FamilyCategory[];
    accounts: FamilyAccount[];
    excludedForeignTransactions: number;
    previousExcludedForeignTransactions: number;
}

export interface FamilyPicture {
    id: string;
    extension: string;
}

export interface FamilyTransaction {
    id: string;
    ownerId: string;
    ownerName: string;
    time: number;
    type: number;
    currency: string;
    amountMinor: string;
    categoryName: string;
    accountName: string;
    comment: string;
    editable: boolean;
    pictures: FamilyPicture[];
}

export interface FamilyScope {
    month: string;
    yearly: boolean;
    member: string;
}

export type FamilyDetailFilter = { kind: 'all' } | { kind: 'date' | 'category'; value: string; label: string };

interface FamilyTransactionPage {
    items: FamilyTransaction[];
    nextCursor: string;
}

interface Envelope<T> {
    success: boolean;
    result: T;
}

export const familyTimeZone = 'Asia/Shanghai';
const currentMonthFormat = new Intl.DateTimeFormat('sv-SE', { timeZone: familyTimeZone, year: 'numeric', month: '2-digit' });

export function currentFamilyMonth(): string {
    return currentMonthFormat.format(new Date());
}

export function familyMinorAmount(value: string | bigint) {
    return parseBigDecimal(value.toString());
}

export function familyMemberLabel(memberId: string, members: FamilyMember[]): string {
    return memberId ? members.find(member => member.id === memberId)?.nickname ?? '当前成员' : '全家';
}

export function familyPeriodLabel(scope: FamilyScope): string {
    const [year, month] = scope.month.split('-');
    return scope.yearly ? `${year} 年` : `${year} 年 ${Number(month)} 月`;
}

export function familyShiftedMonth(scope: FamilyScope, offset: number): string | null {
    const year = Number(scope.month.slice(0, 4));
    const month = Number(scope.month.slice(5, 7));
    const date = new Date(Date.UTC(year + (scope.yearly ? offset : 0), month - 1 + (scope.yearly ? 0 : offset), 1));
    if (date.getUTCFullYear() < 1970 || date.getUTCFullYear() > 9998) {
        return null;
    }
    return date.toISOString().slice(0, 7);
}

export function familyCategoryGroups(categories: FamilyCategory[], type: number): FamilyCategoryGroup[] {
    const groups = new Map<string, FamilyCategoryGroup>();
    for (const category of categories) {
        if (category.type !== type) continue;
        const parent = category.parent || category;
        let group = groups.get(parent.id);
        if (!group) {
            group = { id: parent.id, name: parent.name, icon: parent.icon, amount: 0n, previous: 0n, children: [] };
            groups.set(parent.id, group);
        }
        const amount = BigInt(category.amountMinor);
        const previous = BigInt(category.previousAmountMinor);
        group.amount += amount;
        group.previous += previous;
        group.children.push({ id: category.id, name: category.parent ? category.name : '直接记账', icon: category.icon, amount, previous });
    }
    return Array.from(groups.values());
}

function familyReportParams(scope: FamilyScope) {
    return { period: scope.yearly ? scope.month.slice(0, 4) : scope.month, yearly: scope.yearly, member_id: scope.member || undefined };
}

async function fetchFamilyResult<T>(path: string, params: object, signal: AbortSignal): Promise<T> {
    const { data } = await axios.get<Envelope<T>>(`v1/family/${path}`, { params, signal });
    if (!data.success || !data.result) {
        throw new Error('Unable to load family data');
    }
    return data.result;
}

export function fetchFamilyReport(scope: FamilyScope, signal: AbortSignal): Promise<FamilyReport> {
    return fetchFamilyResult('report.json', familyReportParams(scope), signal);
}

export function fetchFamilyTransactions(scope: FamilyScope, filter: FamilyDetailFilter, cursor: string, signal: AbortSignal): Promise<FamilyTransactionPage> {
    const params = { ...familyReportParams(scope), cursor: cursor || undefined };
    if (filter.kind === 'all') {
        return fetchFamilyResult('transactions/list.json', params, signal);
    }
    return fetchFamilyResult('report/transactions.json', {
        ...params,
        category_id: filter.kind === 'category' ? filter.value : undefined,
        date: filter.kind === 'date' ? filter.value : undefined
    }, signal);
}

export async function fetchFamilyReportCsv(scope: FamilyScope, signal: AbortSignal): Promise<Blob> {
    const { data } = await axios.get<Blob>('v1/family/report/export.csv', { params: familyReportParams(scope), responseType: 'blob', signal });
    if (!data.type.startsWith('text/csv')) {
        throw new Error('Unable to export family report');
    }
    return data;
}

export async function fetchFamilyPicture(picture: FamilyPicture, signal: AbortSignal): Promise<Blob> {
    const name = `${picture.id}.${picture.extension.replace(/^\./, '')}`;
    const { data } = await axios.get<Blob>(`v1/family/pictures/${encodeURIComponent(name)}`, { responseType: 'blob', signal });
    if (!data.type.startsWith('image/')) {
        throw new Error('Unable to load family attachment');
    }
    return data;
}
