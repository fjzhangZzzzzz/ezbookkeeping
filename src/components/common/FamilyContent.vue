<template>
    <div class="family-page">
        <header class="family-heading">
            <div><h1>家庭账本</h1><p>人民币 · 上海时间</p></div>
            <label>月份 <input v-model="month" type="month" aria-label="选择月份" /></label>
        </header>
        <p v-if="error" class="family-error" role="alert">{{ error }} <button @click="reload">重试</button></p>
        <p v-if="loading">正在加载家庭数据…</p>
        <template v-if="overview">
            <p v-if="overview.hasExcludedForeignCurrency" class="family-warning">外币交易未计入家庭收支统计（{{ overview.excludedForeignTransactions }} 笔）。</p>
            <section class="family-card">
                <div class="family-section-title"><h2>本月收支</h2><button :disabled="exporting" @click="exportCsv">{{ exporting ? '导出中…' : '导出汇总 CSV' }}</button></div>
                <div class="family-totals">
                    <div><span>收入</span><strong>¥{{ money(overview.totals.incomeMinor) }}</strong></div>
                    <div><span>支出</span><strong>¥{{ money(overview.totals.expenseMinor) }}</strong></div>
                    <div><span>结余</span><strong>¥{{ money(overview.totals.netMinor) }}</strong></div>
                </div>
            </section>
            <section class="family-card"><h2>支出分类</h2><p class="family-note">按净支出列示，退款抵减支出。</p>
                <p v-if="!overview.categories.length">本月暂无分类支出</p>
                <div v-for="category in overview.categories" :key="category.id" class="family-row"><span>{{ category.name }}<template v-if="canShowPercent"> · {{ categoryPercent(category.expenseMinor) }}%</template></span><strong>¥{{ money(category.expenseMinor) }}</strong></div>
            </section>
            <section class="family-card"><h2>成员收支</h2>
                <div v-for="member in overview.members" :key="member.id" class="family-row family-multiline"><strong>{{ member.nickname }}</strong><span>收入 ¥{{ money(member.incomeMinor) }} · 支出 ¥{{ money(member.expenseMinor) }} · 结余 ¥{{ money(member.netMinor) }}</span></div>
                <p v-if="!overview.members.length">暂无成员数据</p>
            </section>
            <section class="family-card"><h2>最近十二个月</h2>
                <div v-for="item in overview.trend" :key="item.month" class="family-row family-multiline"><strong>{{ item.month }}</strong><span>收入 ¥{{ money(item.incomeMinor) }} · 支出 ¥{{ money(item.expenseMinor) }} · 结余 ¥{{ money(item.netMinor) }}</span></div>
            </section>
            <section class="family-card"><h2>成员账户余额</h2>
                <div v-for="account in overview.accounts" :key="account.id" class="family-row"><span>{{ account.ownerName }} · {{ account.name }}{{ account.hidden ? '（已隐藏）' : '' }}</span><strong>{{ account.currency }} {{ money(account.balanceMinor) }}</strong></div>
                <p v-if="!overview.accounts.length">暂无账户</p>
            </section>
            <section class="family-card"><div class="family-section-title"><h2>家庭明细</h2>
                <label>成员 <select v-model="memberId"><option value="">全部成员</option><option v-for="member in overview.members" :key="member.id" :value="member.id">{{ member.nickname }}</option></select></label></div>
                <p v-if="transactionsLoading && !items.length">正在加载明细…</p>
                <p v-if="!transactionsLoading && !items.length">本月暂无明细</p>
                <article v-for="item in items" :key="item.ownerId + ':' + item.id" class="family-transaction">
                    <div class="family-row"><strong>{{ item.categoryName || typeName(item.type) }}</strong><strong>{{ item.currency }} {{ money(item.amountMinor) }}</strong></div>
                    <p>{{ item.ownerName }} · {{ item.accountName }} · {{ formatTime(item.time) }} · {{ typeName(item.type) }}</p>
                    <p v-if="item.comment">{{ item.comment }}</p>
                    <a v-if="mobile && item.editable" :href="`/transaction/detail?id=${encodeURIComponent(item.id)}&type=${Math.min(item.type, 4)}`" class="family-mobile-link">查看本人交易</a>
                    <button v-if="item.pictures.length" @click="showPictures(item)">查看附件（{{ item.pictures.length }}）</button>
                    <div class="family-pictures"><img v-for="picture in pictureUrls[item.id] || []" :key="picture" :src="picture" alt="交易附件" /></div>
                </article>
                <button v-if="nextCursor" :disabled="transactionsLoading" @click="loadTransactions(true)">{{ transactionsLoading ? '加载中…' : '加载更多' }}</button>
            </section>
        </template>
    </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue';
import axios from 'axios';
import Decimal from 'decimal.js';
import '@/lib/services.ts';

defineProps<{ mobile?: boolean }>();
const RatioDecimal = Decimal.clone({ precision: 50 });
interface Amounts { incomeMinor: string; expenseMinor: string; netMinor: string }
interface Member extends Amounts { id: string; nickname: string }
interface Overview { totals: Amounts; members: Member[]; categories: { id: string; name: string; expenseMinor: string }[]; trend: (Amounts & { month: string })[]; accounts: { id: string; ownerName: string; name: string; currency: string; balanceMinor: string; hidden: boolean }[]; hasExcludedForeignCurrency: boolean; excludedForeignTransactions: number }
interface Transaction { id: string; ownerId: string; ownerName: string; time: number; type: number; currency: string; amountMinor: string; categoryName: string; accountName: string; comment: string; editable: boolean; pictures: { id: string; extension: string }[] }
interface Envelope<T> { success: boolean; result: T }
const month = ref(new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit' }).format(new Date()));
const memberId = ref('');
const overview = ref<Overview | null>(null);
const items = ref<Transaction[]>([]);
const nextCursor = ref('');
const loading = ref(false);
const transactionsLoading = ref(false);
const exporting = ref(false);
const error = ref('');
const pictureUrls = ref<Record<string, string[]>>({});
let overviewSequence = 0;
let transactionSequence = 0;
let pictureSequence = 0;
let disposed = false;
const canShowPercent = computed(() => !!overview.value && new Decimal(overview.value.totals.expenseMinor).greaterThan(0) && overview.value.categories.every(category => new Decimal(category.expenseMinor).greaterThanOrEqualTo(0)));

function money(value: string): string {
    const negative = value.startsWith('-');
    const digits = (negative ? value.slice(1) : value).padStart(3, '0');
    return `${negative ? '-' : ''}${digits.slice(0, -2)}.${digits.slice(-2)}`;
}
function categoryPercent(value: string): string { return new RatioDecimal(value).dividedBy(overview.value!.totals.expenseMinor).times(100).toFixed(1); }
function typeName(type: number): string { return ({ 1: '余额调整', 2: '收入', 3: '支出', 4: '转出', 5: '转入' } as Record<number, string>)[type] || '交易'; }
function formatTime(time: number): string { return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(time * 1000)); }
function clearPictures(): void { pictureSequence++; Object.values(pictureUrls.value).flat().forEach(url => URL.revokeObjectURL(url)); pictureUrls.value = {}; }
async function loadOverview(): Promise<void> {
    const sequence = ++overviewSequence;
    loading.value = true; overview.value = null; error.value = '';
    try {
        const response = await axios.get<Envelope<Overview>>('v1/family/overview.json', { params: { month: month.value } });
        if (!response.data.success || !response.data.result) throw new Error();
        if (sequence === overviewSequence && !disposed) overview.value = response.data.result;
    } catch { if (sequence === overviewSequence && !disposed) error.value = '家庭统计加载失败，请检查网络后重试。'; }
    finally { if (sequence === overviewSequence && !disposed) loading.value = false; }
}
async function loadTransactions(append = false): Promise<void> {
    const sequence = ++transactionSequence;
    const cursor = append ? nextCursor.value : '';
    if (!append) { items.value = []; nextCursor.value = ''; clearPictures(); }
    transactionsLoading.value = true;
    try {
        const response = await axios.get<Envelope<{ items: Transaction[]; nextCursor: string }>>('v1/family/transactions/list.json', { params: { month: month.value, member_id: memberId.value || undefined, cursor: cursor || undefined, count: 50 } });
        if (!response.data.success || !response.data.result) throw new Error();
        if (sequence === transactionSequence && !disposed) { items.value = append ? [...items.value, ...response.data.result.items] : response.data.result.items; nextCursor.value = response.data.result.nextCursor; }
    } catch { if (sequence === transactionSequence && !disposed) error.value = '家庭明细加载失败，请检查网络后重试。'; }
    finally { if (sequence === transactionSequence && !disposed) transactionsLoading.value = false; }
}
async function showPictures(item: Transaction): Promise<void> {
    if (pictureUrls.value[item.id]) return;
    const sequence = pictureSequence;
    const urls: string[] = [];
    try {
        for (const picture of item.pictures) {
            const response = await axios.get<Blob>(`v1/family/pictures/${encodeURIComponent(picture.id + '.' + picture.extension.replace(/^\./, ''))}`, { responseType: 'blob' });
            urls.push(URL.createObjectURL(response.data));
        }
        if (sequence !== pictureSequence || disposed) { urls.forEach(url => URL.revokeObjectURL(url)); return; }
        pictureUrls.value = { ...pictureUrls.value, [item.id]: urls };
    } catch { urls.forEach(url => URL.revokeObjectURL(url)); if (sequence === pictureSequence && !disposed) error.value = '附件加载失败，请重试。'; }
}
async function exportCsv(): Promise<void> {
    exporting.value = true;
    try {
        const response = await axios.get<Blob>('v1/family/export.csv', { params: { month: month.value }, responseType: 'blob' });
        const url = URL.createObjectURL(response.data); const link = document.createElement('a'); link.href = url; link.download = `family-${month.value}.csv`; link.click(); URL.revokeObjectURL(url);
    } catch { error.value = '导出失败，请检查网络后重试。'; }
    finally { exporting.value = false; }
}
function reload(): void { void loadOverview(); void loadTransactions(); }
watch(month, () => { memberId.value = ''; reload(); });
watch(memberId, () => { void loadTransactions(); });
onMounted(reload);
onUnmounted(() => { disposed = true; overviewSequence++; transactionSequence++; clearPictures(); });
</script>

<style scoped>
.family-page { max-width: 960px; margin: auto; padding: 20px 16px 48px; color: var(--f7-text-color, inherit); }
.family-heading, .family-section-title, .family-row { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.family-heading { flex-wrap: wrap; margin-bottom: 18px; }.family-heading h1 { margin: 0; font-size: 1.7rem; }.family-heading p, .family-note { opacity: .7; margin: 4px 0; }
.family-card { background: var(--f7-card-bg-color, #fff); border: 1px solid rgba(128,128,128,.2); border-radius: 14px; padding: 16px; margin: 12px 0; }.family-card h2 { font-size: 1.1rem; margin: 0 0 12px; }.family-section-title h2 { margin: 0; }.family-section-title { margin-bottom: 12px; flex-wrap: wrap; }
.family-totals { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }.family-totals span, .family-totals strong { display: block; }.family-totals span { opacity: .7; }.family-totals strong { margin-top: 6px; overflow-wrap: anywhere; }
.family-row { padding: 9px 0; border-bottom: 1px solid rgba(128,128,128,.15); }.family-row:last-child { border-bottom: 0; }.family-multiline { flex-wrap: wrap; }.family-multiline span { font-size: .9em; }.family-transaction { padding: 8px 0; border-bottom: 1px solid rgba(128,128,128,.2); }.family-transaction .family-row { border: 0; }.family-transaction p { margin: 4px 0; opacity: .75; }.family-pictures img { max-width: 100%; max-height: 300px; margin: 8px 8px 0 0; }.family-warning, .family-error { padding: 12px; border-radius: 8px; background: #fff1cf; color: #663b00; }.family-error { background: #ffe6e6; color: #8b2222; } button, input, select { font: inherit; padding: 6px; } button { cursor: pointer; }
</style>
