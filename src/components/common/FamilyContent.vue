<template>
    <main ref="reportPage" class="family-page" :class="{ 'family-mobile': mobile }">
        <header :class="{ 'family-heading': !mobile, 'family-mobile-actions': mobile }">
            <h1 :class="{ 'family-sr-only': mobile }">家庭账本</h1>
            <component :is="actionsContainer" :class="mobile ? 'family-actions-menu' : 'family-actions'">
                <div :class="{ 'family-actions-menu-content': mobile }">
                    <component :is="button" v-bind="buttonProps" :popover-close="mobile ? true : undefined" @click="openDetails({ kind: 'all' })">全部家庭流水</component>
                    <component :is="button" v-bind="buttonProps" :popover-close="mobile ? true : undefined" :disabled="exportDisabled" :aria-disabled="exportDisabled" @click="exportReport">
                        {{ exporting ? '导出中…' : '导出汇总 CSV' }}
                    </component>
                </div>
            </component>
        </header>
        <div v-if="exportError" class="family-error" role="alert">{{ exportError }}</div>
        <component v-if="yearOrigin" :is="button" v-bind="buttonProps" @click="returnToYear">‹ 返回 {{ familyPeriodLabel(yearOrigin) }}</component>
        <family-scope-controls v-model="scope" :members="members" :mobile="mobile" />
        <div v-if="reportError" class="family-error" role="alert">
            {{ reportError }} <component :is="button" v-bind="buttonProps" @click="loadReport">重试</component>
        </div>
        <component v-else-if="loading" :is="card" class="family-card family-loading" role="status">
            <component :is="progress" v-bind="progressProps" aria-hidden="true" /> 正在加载家庭报表…
        </component>
        <template v-else-if="report">
            <component :is="card" class="family-card family-summary">
                <div class="family-card-title">
                    <h2>收支总览</h2>
                    <p class="family-note">人民币 · 上海时间</p>
                </div>
                <div class="family-metrics">
                    <div v-for="metric in metrics" :key="metric.label">
                        <span>{{ metric.label }}</span>
                        <strong :style="{ color: metric.color }">{{ amount(metric.value) }}</strong>
                    </div>
                </div>
                <p v-if="currentForeignCount > 0" class="family-notice">{{ currentForeignCount }} 笔外币交易未计入</p>
            </component>

            <component :is="card" class="family-card">
                <div class="family-card-title">
                    <h2>{{ scope.yearly ? '月度对比' : '每日统计' }}</h2>
                    <div class="family-actions">
                        <component :is="button" v-bind="buttonProps" :disabled="!dateRows.length" @click="line = !line">{{ line ? '柱状图' : '折线图' }}</component>
                        <component :is="button" v-bind="buttonProps" :disabled="!dateRows.length" @click="openChart">放大</component>
                    </div>
                </div>
                <family-segmented-control v-model="trend" class="family-trend-series" :mobile="mobile" label="趋势系列" :options="trendOptions" />
                <family-trend-chart v-if="dateRows.length" :items="trendItems" :labels="trendLabels" :yearly="scope.yearly" :line="line" :dark-mode="darkMode" />
                <p v-else class="family-empty">所选期间暂无收支</p>
            </component>

            <component :is="card" class="family-card">
                <div class="family-card-title">
                    <h2>分类报表</h2>
                    <family-segmented-control v-model="categoryType" :mobile="mobile" label="分类系列" :options="categoryOptions" />
                </div>
                <p v-if="report.previousExcludedForeignTransactions > 0" class="family-note">上期有 {{ report.previousExcludedForeignTransactions }} 笔外币交易未计入</p>
                <family-category-breakdown :rows="categoryGroups" :palette="palette" :yearly="scope.yearly"
                                           :is-expense="categoryType === CategoryType.Expense" :mobile="mobile" @select="openCategory" />
            </component>

            <component :is="card" class="family-card">
                <h2>{{ scope.yearly ? '月报表' : '日报表' }}</h2>
                <div v-if="dateRows.length" class="family-table-wrap">
                    <table class="family-table">
                        <thead><tr><th scope="col">{{ scope.yearly ? '月份' : '日期' }}</th><th scope="col">支出</th><th scope="col">收入</th><th scope="col">结余</th></tr></thead>
                        <tbody>
                            <tr v-for="row in dateRows" :key="row.period">
                                <th scope="row"><button class="family-date-link" type="button" :data-family-period="row.period" @click="openDate(row.period)">{{ displayRowDate(row.period) }} ›</button></th>
                                <td>{{ amount(row.expenseMinor) }}</td><td>{{ amount(row.incomeMinor) }}</td><td>{{ amount(row.netMinor) }}</td>
                            </tr>
                            <tr v-if="scope.yearly">
                                <th scope="row">平均</th><td>{{ amount(report.averageExpenseMinor) }}</td><td>{{ amount(report.averageIncomeMinor) }}</td><td>{{ amount(report.averageNetMinor) }}</td>
                            </tr>
                        </tbody>
                    </table>
                </div>
                <p v-else class="family-empty">所选期间暂无收支</p>
            </component>

            <div class="family-support-grid">
                <component v-if="!scope.member" :is="card" class="family-card">
                    <h2>成员对比</h2>
                    <button v-for="member in report.members" :key="member.id" class="family-list-row" type="button" @click="scope = { ...scope, member: member.id }">
                        <strong>{{ member.nickname }}</strong>
                        <span class="family-row-amount"><small>收入 {{ amount(member.incomeMinor) }}</small><br /><small>支出 {{ amount(member.expenseMinor) }} ›</small></span>
                    </button>
                </component>
                <component :is="card" class="family-card">
                    <h2>当前账户余额</h2>
                    <div v-for="group in accountGroups" :key="group.ownerId" class="family-account-group">
                        <h3>{{ group.ownerName }}</h3>
                        <div v-for="account in group.accounts" :key="account.id" class="family-list-row">
                            <span>{{ account.name }} <small v-if="account.hidden">（已隐藏）</small></span>
                            <strong class="family-row-amount">{{ amount(account.balanceMinor, account.currency, CurrencyDisplayType.CodeBeforeAmount) }}</strong>
                        </div>
                    </div>
                    <p v-if="!accountGroups.length" class="family-empty">暂无账户</p>
                </component>
            </div>
        </template>
    </main>

    <dialog ref="categoryDialog" class="family-dialog family-detail-dialog" :class="{ 'family-detail-mobile': mobile }"
            aria-label="二级分类" @close="onCategoryClosed">
        <family-dialog-header :mobile="mobile" :title="`${activeGroup?.name || ''} · 二级分类`" :subtitle="categorySubtitle"
                              close-label="返回分类报表" @close="categoryDialog?.close()" />
        <div v-if="activeGroup" class="family-dialog-body">
            <family-category-breakdown :key="activeGroup.id" :rows="activeGroup.children" :palette="palette" :yearly="scope.yearly"
                                       :is-expense="categoryType === CategoryType.Expense" :mobile="mobile" @select="openChildCategory" />
        </div>
    </dialog>

    <dialog ref="detailDialog" class="family-dialog family-detail-dialog" :class="{ 'family-detail-mobile': mobile }"
            aria-labelledby="family-detail-title" @close="onDetailsClosed">
        <family-dialog-header :mobile="mobile" :title="selectedTransaction ? '交易详情' : detailTitle" title-id="family-detail-title"
                              :subtitle="detailSubtitle" :back-label="selectedTransaction ? '返回明细' : undefined"
                              @back="backToDetails" @close="detailDialog?.close()" />
        <div v-if="selectedTransaction" class="family-dialog-body">
            <p class="family-detail-amount">{{ amount(selectedTransaction.amountMinor, selectedTransaction.currency) }}</p>
            <dl class="family-detail-fields">
                <dt>类型</dt><dd>{{ typeName(selectedTransaction.type) }}</dd>
                <dt>分类</dt><dd>{{ selectedTransaction.categoryName || '未分类' }}</dd>
                <dt>日期</dt><dd>{{ transactionDate(selectedTransaction.time) }}</dd>
                <dt>成员</dt><dd>{{ selectedTransaction.ownerName }}</dd>
                <dt>账户</dt><dd>{{ selectedTransaction.accountName }}</dd>
                <template v-if="selectedTransaction.comment"><dt>备注</dt><dd>{{ selectedTransaction.comment }}</dd></template>
            </dl>
            <p v-if="pictureError" class="family-error" role="alert">附件加载失败，请重试。</p>
            <div v-if="selectedTransaction.pictures.length" class="family-attachments">
                <component v-if="!pictureUrls.length" :is="button" v-bind="buttonProps" :disabled="pictureLoading" @click="loadPictures">
                    {{ pictureLoading ? '正在加载附件…' : `查看附件（${selectedTransaction.pictures.length}）` }}
                </component>
                <component v-for="(url, index) in pictureUrls" :key="url" :is="mobile ? 'button' : 'a'"
                           v-bind="mobile ? { type: 'button' } : { href: url, target: '_blank', rel: 'noopener' }"
                           :aria-label="`打开附件 ${index + 1}`" :data-family-picture="index" @click="mobile && openPictures(index)">
                    <img :src="url" :alt="`交易附件 ${index + 1}`" />
                    <span>附件 {{ index + 1 }}</span>
                </component>
            </div>
            <component v-if="selectedTransaction.editable" :is="button" v-bind="buttonProps" @click="openPersonal(selectedTransaction)">在个人账本中打开</component>
        </div>
        <div v-else class="family-dialog-body" ref="detailList">
            <family-scope-controls v-if="detailFilter?.kind === 'all'" v-model="detailScope" :members="members" :mobile="mobile" :menu-container="detailDialog" />
            <p v-if="detailsLoading" class="family-note" role="status">正在加载流水…</p>
            <div v-if="detailsError" class="family-error" role="alert">
                家庭流水加载失败。<component :is="button" v-bind="buttonProps" @click="loadDetails(failedAppend)">重试</component>
            </div>
            <p v-if="!detailsLoading && !detailsError && !transactions.length" class="family-empty">该范围暂无流水</p>
            <component :is="list" v-if="transactions.length" class="family-transactions" v-bind="listProps">
                <component :is="listItem" v-for="transaction in transactions" :key="transaction.id" class="family-transaction"
                           v-bind="listItemProps" :data-family-transaction="transaction.id" @click.prevent="showTransaction(transaction)"
                           @keydown.space.prevent="mobile && showTransaction(transaction)">
                    <template #title><strong>{{ transaction.categoryName || typeName(transaction.type) }}</strong></template>
                    <template #subtitle>{{ transaction.ownerName }} · {{ transaction.accountName }} · {{ transactionDate(transaction.time) }}</template>
                    <template #[amountSlot]>
                        <span class="family-row-amount">
                            <strong :style="{ color: transactionAmountColor(transaction.type) }">{{ amount(transaction.amountMinor, transaction.currency) }}</strong>
                            <small>{{ typeName(transaction.type) }}</small>
                        </span>
                    </template>
                </component>
            </component>
            <component v-if="nextCursor && !detailsError" :is="button" v-bind="buttonProps" :disabled="detailsLoading" @click="loadDetails(true)">加载更多</component>
        </div>
    </dialog>

    <dialog ref="chartDialog" class="family-dialog family-chart-dialog" aria-label="趋势图" @close="chartOpen = false">
        <family-dialog-header :mobile="mobile" :title="scope.yearly ? '月度对比' : '每日统计'" @close="chartDialog?.close()" />
        <div class="family-dialog-body" v-if="chartOpen"><family-trend-chart :items="trendItems" :labels="trendLabels" :yearly="scope.yearly" :line="line" :dark-mode="darkMode" expanded /></div>
    </dialog>
    <component v-if="mobile" :is="photoBrowser" ref="pictureBrowser" type="popup" navbar-of-text="/" popup-close-link-text="关闭"
               :theme="darkMode ? 'dark' : 'light'" :photos="viewerPhotos" @photobrowser:closed="restoreAfterPictures" />
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, resolveComponent, watch } from 'vue';
import type { PhotoBrowser } from 'framework7/types';

import FamilyCategoryBreakdown from '@/components/common/FamilyCategoryBreakdown.vue';
import FamilyDialogHeader from '@/components/common/FamilyDialogHeader.vue';
import FamilyScopeControls from '@/components/common/FamilyScopeControls.vue';
import FamilySegmentedControl from '@/components/common/FamilySegmentedControl.vue';
import FamilyTrendChart from '@/components/common/FamilyTrendChart.vue';
import { useI18n } from '@/locales/helpers.ts';
import { useSettingsStore } from '@/stores/setting.ts';
import { useUserStore } from '@/stores/user.ts';
import { CategoryType } from '@/core/category.ts';
import type { AxisChartSourceDataItem } from '@/core/chart.ts';
import { CurrencyDisplayType } from '@/core/currency.ts';
import { getExpenseAndIncomeAmountColor } from '@/lib/ui/common.ts';
import { DEFAULT_CHART_COLORS } from '@/consts/color.ts';
import {
    currentFamilyMonth, familyMemberLabel, familyMinorAmount, familyPeriodLabel, familyCategoryGroups, familyTimeZone,
    fetchFamilyPicture, fetchFamilyReport, fetchFamilyReportCsv, fetchFamilyTransactions,
    type FamilyAccount, type FamilyAmounts, type FamilyDetailFilter, type FamilyMember,
    type FamilyReport, type FamilyScope, type FamilyTransaction
} from '@/lib/family.ts';

const props = defineProps<{ mobile: boolean; darkMode: boolean }>();
const emit = defineEmits<{ (e: 'open-personal', transaction: FamilyTransaction): void }>();
const { formatAmountToLocalizedNumeralsWithCurrency } = useI18n();
const settings = useSettingsStore();
const user = useUserStore();
const actionsContainer = props.mobile ? resolveComponent('f7-popover') : 'div';
const card = resolveComponent(props.mobile ? 'f7-card' : 'v-card');
const button = resolveComponent(props.mobile ? 'f7-button' : 'v-btn');
const list = resolveComponent(props.mobile ? 'f7-list' : 'v-list');
const listItem = resolveComponent(props.mobile ? 'f7-list-item' : 'v-list-item');
const photoBrowser = props.mobile ? resolveComponent('f7-photo-browser') : null;
const pictureBrowser = ref<PhotoBrowser.PhotoBrowser>();
const progress = resolveComponent(props.mobile ? 'f7-preloader' : 'v-progress-circular');
const progressProps = props.mobile ? { size: 22 } : { size: 22, indeterminate: true, color: 'primary' };
const buttonProps = props.mobile ? { small: true, type: 'button' } : { variant: 'text', size: 'small', color: 'primary' };
const listProps = props.mobile ? { dividers: true, mediaList: true } : { density: 'compact', tag: 'ul', role: 'list' };
const listItemProps = props.mobile ? { link: '#' } : { tag: 'li', link: true, role: 'button' };
const amountSlot = props.mobile ? 'after' : 'append';
const dateFormat = new Intl.DateTimeFormat('zh-CN', { timeZone: familyTimeZone, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false });
const zeroAmounts: FamilyAmounts = { incomeMinor: '0', expenseMinor: '0', netMinor: '0' };

const scope = ref<FamilyScope>({ month: currentFamilyMonth(), yearly: false, member: '' });
const reportPage = ref<HTMLElement>();
const yearOrigin = ref<FamilyScope>();
const report = ref<FamilyReport>();
const members = ref<FamilyMember[]>([]);
const reportError = ref('');
const loading = ref(true);
const categoryType = ref(CategoryType.Expense);
const categoryOptions = [{ label: '支出', value: CategoryType.Expense }, { label: '收入', value: CategoryType.Income }];
const trend = ref('expense');
const line = ref(false);
const chartOpen = ref(false);
const chartDialog = ref<HTMLDialogElement>();
const exportError = ref('');
const exporting = ref(false);
const exportDisabled = computed(() => exporting.value || loading.value || !report.value || !!reportError.value);
let reportRequest: AbortController | undefined;
let exportRequest: AbortController | undefined;
let pendingDateFocus = '';
let yearOriginRow = '';

const detailDialog = ref<HTMLDialogElement>();
const detailList = ref<HTMLElement>();
const detailScope = ref<FamilyScope>({ ...scope.value });
const detailFilter = ref<FamilyDetailFilter>();
const detailsLoading = ref(false);
const detailsError = ref(false);
const failedAppend = ref(false);
const selectedTransaction = ref<FamilyTransaction>();
const transactions = ref<FamilyTransaction[]>([]);
const nextCursor = ref('');
const pictureUrls = ref<string[]>([]);
const viewerPhotos = computed(() => pictureUrls.value.map(url => ({ url })));
let pictureReturn: { categoryScroll: number | null; scrollTop: number; index: number } | undefined;
const pictureLoading = ref(false);
const pictureError = ref(false);
let detailsRequest: AbortController | undefined;
let pictureRequest: AbortController | undefined;
let resumeDetails = false;
let detailScroll = 0;
let detailFocusId = '';

const amountColors = computed(() => getExpenseAndIncomeAmountColor(user.currentUserExpenseAmountColor, user.currentUserIncomeAmountColor, props.darkMode));
const seriesOptions = computed(() => [
    { id: 'expense', label: '支出', field: 'expenseMinor' as const, color: amountColors.value.expenseAmountColor.slice(1) },
    { id: 'income', label: '收入', field: 'incomeMinor' as const, color: amountColors.value.incomeAmountColor.slice(1) },
    { id: 'net', label: '结余', field: 'netMinor' as const, color: '946443' }
]);
const enabledSeries = computed(() => scope.value.yearly ? seriesOptions.value : seriesOptions.value.slice(0, 2));
const trendOptions = computed(() => [...enabledSeries.value.map(series => ({ label: series.label, value: series.id })), { label: '全部', value: 'all' }]);
const metrics = computed(() => report.value ? [
    { label: '支出', value: report.value.expenseMinor, color: amountColors.value.expenseAmountColor },
    { label: '收入', value: report.value.incomeMinor, color: amountColors.value.incomeAmountColor },
    { label: '结余', value: report.value.netMinor, color: undefined },
    { label: scope.value.yearly ? '月均支出' : '日均支出', value: report.value.averageExpenseMinor, color: undefined }
] : []);
const currentForeignCount = computed(() => report.value ? report.value.excludedForeignTransactions - report.value.previousExcludedForeignTransactions : 0);
const dateRows = computed(() => {
    if (!report.value) return [];
    if (scope.value.yearly) return report.value.months.map(row => ({ ...row, period: row.month })).reverse();
    return report.value.days.filter(row => row.incomeMinor !== '0' || row.expenseMinor !== '0').map(row => ({ ...row, period: row.date })).reverse();
});
const trendPeriods = computed(() => {
    const [year, month] = scope.value.month.split('-');
    const count = scope.value.yearly ? 12 : new Date(Date.UTC(Number(year), Number(month), 0)).getUTCDate();
    const prefix = scope.value.yearly ? year : scope.value.month;
    return Array.from({ length: count }, (_, index) => `${prefix}-${String(index + 1).padStart(2, '0')}`);
});
const trendLabels = computed(() => trendPeriods.value.map(displayRowDate));
const trendItems = computed<AxisChartSourceDataItem[]>(() => {
    const amounts = new Map(dateRows.value.map(row => [row.period, row]));
    return enabledSeries.value.filter(series => trend.value === 'all' || trend.value === series.id).map((series, index) => ({
        id: series.id, name: series.label, color: series.color, displayOrders: [index],
        values: trendPeriods.value.map(period => familyMinorAmount((amounts.get(period) || zeroAmounts)[series.field]))
    }));
});
const palette = computed(() => settings.chartColorList.length ? settings.chartColorList : DEFAULT_CHART_COLORS);
const categoryDialog = ref<HTMLDialogElement>();
const activeGroupId = ref('');
const categoryGroups = computed(() => familyCategoryGroups([
    ...(report.value?.categories || []), ...(report.value?.priorOnlyCategories || [])
], categoryType.value));
const activeGroup = computed(() => categoryGroups.value.find(group => group.id === activeGroupId.value));
const categorySubtitle = computed(() => `${familyPeriodLabel(scope.value)} · ${familyMemberLabel(scope.value.member, members.value)} · ${categoryType.value === CategoryType.Expense ? '支出' : '收入'}`);
const detailSubtitle = computed(() => `${familyPeriodLabel(detailScope.value)} · ${familyMemberLabel(detailScope.value.member, members.value)} · 只读`);
const accountGroups = computed(() => {
    const groups = new Map<string, { ownerId: string; ownerName: string; accounts: FamilyAccount[] }>();
    for (const account of report.value?.accounts || []) {
        const group = groups.get(account.ownerId) || { ownerId: account.ownerId, ownerName: account.ownerName, accounts: [] };
        group.accounts.push(account);
        groups.set(account.ownerId, group);
    }
    return Array.from(groups.values());
});
const detailTitle = computed(() => {
    if (detailFilter.value?.kind === 'category') return `${detailFilter.value.label}明细`;
    if (detailFilter.value?.kind === 'date') return `${detailFilter.value.label}明细`;
    return '全部家庭流水';
});

function amount(value: string | bigint, currency = 'CNY', display?: CurrencyDisplayType): string {
    return formatAmountToLocalizedNumeralsWithCurrency(familyMinorAmount(value), currency, display);
}

function transactionDate(time: number): string {
    return dateFormat.format(new Date(time * 1000));
}

function typeName(type: number): string {
    return ['', '余额调整', '收入', '支出', '转出', '转入'][type] || '交易';
}

function transactionAmountColor(type: number): string | undefined {
    if (type === 2) return amountColors.value.incomeAmountColor;
    if (type === 3) return amountColors.value.expenseAmountColor;
    return undefined;
}

function displayRowDate(period: string): string {
    if (period.length === 7) return `${Number(period.slice(5))} 月`;
    return `${Number(period.slice(5, 7))}/${Number(period.slice(8))}`;
}

async function loadReport(): Promise<void> {
    reportRequest?.abort();
    const request = reportRequest = new AbortController();
    const activeScope = scope.value;
    reportError.value = '';
    loading.value = true;
    try {
        const data = await fetchFamilyReport(activeScope, request.signal);
        if (request.signal.aborted) return;
        report.value = data;
        members.value = data.members;
    } catch {
        if (!request.signal.aborted) reportError.value = '家庭报表加载失败。';
    } finally {
        if (!request.signal.aborted) loading.value = false;
    }
    if (!request.signal.aborted && !reportError.value && pendingDateFocus) {
        const period = pendingDateFocus;
        pendingDateFocus = '';
        await nextTick();
        reportPage.value?.querySelector<HTMLElement>(`[data-family-period="${CSS.escape(period)}"]`)?.focus();
    }
}

function openDate(period: string): void {
    if (scope.value.yearly) {
        yearOrigin.value = { ...scope.value };
        yearOriginRow = period;
        scope.value = { ...scope.value, month: period, yearly: false };
        reportPage.value?.scrollIntoView({ block: 'start' });
    } else {
        void openDetails({ kind: 'date', value: period, label: displayRowDate(period) });
    }
}

function returnToYear(): void {
    if (yearOrigin.value) {
        pendingDateFocus = yearOriginRow;
        scope.value = yearOrigin.value;
        yearOrigin.value = undefined;
    }
}

function openChart(): void {
    chartOpen.value = true;
    chartDialog.value?.showModal();
}

async function openCategory(id: string): Promise<void> {
    activeGroupId.value = id;
    await nextTick();
    categoryDialog.value?.showModal();
}

function openChildCategory(id: string): void {
    const group = activeGroup.value;
    const category = group?.children.find(child => child.id === id);
    if (group && category) {
        void openDetails({ kind: 'category', value: id, label: id === group.id ? group.name : category.name });
    }
}

function onCategoryClosed(): void {
    if (resumeDetails || pictureReturn) return;
    reportPage.value?.querySelector<HTMLElement>(`[data-family-category="${CSS.escape(activeGroupId.value)}"]`)?.focus({ preventScroll: true });
    activeGroupId.value = '';
}

async function openDetails(filter: FamilyDetailFilter): Promise<void> {
    detailFilter.value = filter;
    detailScope.value = { ...scope.value };
    selectedTransaction.value = undefined;
    detailScroll = 0;
    detailFocusId = '';
    await nextTick();
    detailDialog.value?.showModal();
    void loadDetails();
}

async function loadDetails(append = false): Promise<void> {
    if (!detailFilter.value || !detailDialog.value?.open || (append && (!nextCursor.value || detailsLoading.value))) return;
    detailsRequest?.abort();
    const request = detailsRequest = new AbortController();
    const cursor = append ? nextCursor.value : '';
    if (!append) {
        transactions.value = [];
        nextCursor.value = '';
    }
    detailsLoading.value = true;
    detailsError.value = false;
    try {
        const page = await fetchFamilyTransactions(detailScope.value, detailFilter.value, cursor, request.signal);
        if (request.signal.aborted) return;
        transactions.value = append ? [...transactions.value, ...page.items] : page.items;
        nextCursor.value = page.nextCursor;
    } catch {
        if (!request.signal.aborted) {
            failedAppend.value = append;
            detailsError.value = true;
        }
    } finally {
        if (!request.signal.aborted) detailsLoading.value = false;
    }
}

function showTransaction(transaction: FamilyTransaction): void {
    detailScroll = detailDialog.value?.scrollTop || 0;
    detailFocusId = transaction.id;
    selectedTransaction.value = transaction;
    detailDialog.value?.scrollTo(0, 0);
    void nextTick(() => detailDialog.value?.querySelector<HTMLElement>('button')?.focus());
}

function clearPictures(): void {
    pictureRequest?.abort();
    pictureUrls.value.forEach(url => URL.revokeObjectURL(url));
    pictureUrls.value = [];
    pictureLoading.value = false;
    pictureError.value = false;
}

function backToDetails(): void {
    clearPictures();
    selectedTransaction.value = undefined;
    void nextTick(() => {
        detailDialog.value?.scrollTo(0, detailScroll);
        const row = detailList.value?.querySelector<HTMLElement>(`[data-family-transaction="${CSS.escape(detailFocusId)}"]`);
        (row?.querySelector('a') || row)?.focus({ preventScroll: true });
    });
}

function onDetailsClosed(): void {
    if (pictureReturn) return;
    detailsRequest?.abort();
    detailsLoading.value = false;
    clearPictures();
    selectedTransaction.value = undefined;
    if (!resumeDetails) {
        if (detailFilter.value?.kind === 'category' && categoryDialog.value?.open) {
            categoryDialog.value.querySelector<HTMLElement>(`[data-family-category="${CSS.escape(detailFilter.value.value)}"]`)?.focus({ preventScroll: true });
        }
        detailFilter.value = undefined;
    }
}

function openPictures(index: number): void {
    if (!pictureBrowser.value || !detailDialog.value?.open || pictureReturn) return;
    pictureReturn = { categoryScroll: categoryDialog.value?.open ? categoryDialog.value.scrollTop : null, scrollTop: detailDialog.value.scrollTop, index };
    detailDialog.value.close();
    if (pictureReturn.categoryScroll !== null) categoryDialog.value?.close();
    pictureBrowser.value.open(index);
}

function restoreAfterPictures(): void {
    const state = pictureReturn;
    pictureReturn = undefined;
    if (!state || !detailDialog.value?.isConnected) return;
    if (state.categoryScroll !== null && categoryDialog.value) {
        categoryDialog.value.showModal();
        categoryDialog.value.scrollTop = state.categoryScroll;
    }
    detailDialog.value.showModal();
    detailDialog.value.scrollTop = state.scrollTop;
    detailDialog.value.querySelector<HTMLElement>(`[data-family-picture="${state.index}"]`)?.focus({ preventScroll: true });
}

async function loadPictures(): Promise<void> {
    if (pictureLoading.value || !selectedTransaction.value) return;
    const request = pictureRequest = new AbortController();
    pictureLoading.value = true;
    pictureError.value = false;
    try {
        const pictures = await Promise.all(selectedTransaction.value.pictures.map(picture => fetchFamilyPicture(picture, request.signal)));
        if (!request.signal.aborted) pictureUrls.value = pictures.map(picture => URL.createObjectURL(picture));
    } catch {
        if (!request.signal.aborted) pictureError.value = true;
        request.abort();
    } finally {
        if (request === pictureRequest) pictureLoading.value = false;
    }
}

function openPersonal(transaction: FamilyTransaction): void {
    resumeDetails = true;
    detailDialog.value?.close();
    categoryDialog.value?.close();
    emit('open-personal', transaction);
}

function resumePersonal(): void {
    if (!resumeDetails) return;
    resumeDetails = false;
    void loadReport();
    if (activeGroup.value) categoryDialog.value?.showModal();
    if (detailFilter.value) {
        detailDialog.value?.showModal();
        void loadDetails();
    }
}

async function exportReport(): Promise<void> {
    if (exportDisabled.value) return;
    const request = exportRequest = new AbortController();
    const activeScope = scope.value;
    exporting.value = true;
    exportError.value = '';
    try {
        const data = await fetchFamilyReportCsv(activeScope, request.signal);
        if (request.signal.aborted) return;
        const url = URL.createObjectURL(data);
        const link = document.createElement('a');
        link.href = url;
        link.download = `family-${activeScope.yearly ? activeScope.month.slice(0, 4) : activeScope.month}.csv`;
        link.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch {
        if (!request.signal.aborted) exportError.value = '导出汇总失败，请重试。';
    } finally {
        if (!request.signal.aborted) exporting.value = false;
    }
}

watch(scope, () => {
    if (!scope.value.yearly && trend.value === 'net') trend.value = 'expense';
    if (scope.value.yearly) yearOrigin.value = undefined;
    exportError.value = '';
    void loadReport();
}, { immediate: true });
watch(detailScope, () => {
    if (detailFilter.value?.kind === 'all' && detailDialog.value?.open) void loadDetails();
});
onBeforeUnmount(() => {
    pictureReturn = undefined;
    reportRequest?.abort();
    detailsRequest?.abort();
    exportRequest?.abort();
    clearPictures();
});

defineExpose({ resumePersonal });
</script>

<style src="@/styles/common/family-report.scss"></style>
