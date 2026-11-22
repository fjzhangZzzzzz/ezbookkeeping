<template>
    <div v-if="ranked.length" class="family-category-layout">
        <div v-if="positiveTotal > 0n" class="family-category-figure">
            <div class="family-pie-area" @pointerdown="startRotation" @pointermove="rotate" @pointerup="endRotation"
                 @pointercancel="endRotation" @lostpointercapture="endRotation" @click.capture="cancelDragClick">
                <pie-chart class="family-pie" :items="pieItems" :value-type="ChartValueType.Amount"
                           v-bind="pieProps" :rotation="rotation" default-currency="CNY"
                           show-value show-percent use-custom-color enable-click-item aria-hidden="true" @click="openSlice" />
                <div class="family-pie-center">
                    <small>正净额合计</small>
                    <strong :title="amount(positiveTotal)">{{ amount(positiveTotal) }}</strong>
                </div>
            </div>
            <p class="family-note">正净额分类占比 · 可拖动旋转</p>
            <button v-if="remainingPositiveCount" type="button" class="family-category-more" @click="showRemaining">
                <span class="family-remainder-swatch" aria-hidden="true" />其余分类（{{ remainingPositiveCount }} 项）
            </button>
        </div>
        <p v-else class="family-note">暂无正净额可绘制环图</p>
        <div class="family-category-net">
            <span>{{ isExpense ? '净支出' : '净收入' }}</span>
            <strong>{{ amount(netTotal) }}</strong>
        </div>
        <p v-if="negativeCount" class="family-note">{{ negativeCount }} 项负净额未绘入环图，已计入净额</p>
        <div ref="rowList" class="family-category-rows">
            <button v-for="category in visibleRows" :key="category.id" type="button" class="family-list-row family-category" :data-family-category="category.id"
                    @click="emit('select', category.id)">
                <span class="family-category-name">
                    <item-icon icon-type="category" :icon-id="category.icon" :color="category.color" aria-hidden="true" />
                    <span>{{ category.name }}</span>
                </span>
                <span class="family-row-amount">
                    <strong>{{ amount(category.amount) }} <span aria-hidden="true">›</span></strong>
                    <span class="family-category-comparison">
                        <small v-if="category.amount > 0n">{{ percent(category.amount) }} · </small>
                        <small>较{{ yearly ? '上年' : '上月' }} {{ category.difference > 0n ? '+' : '' }}{{ amount(category.difference) }}</small>
                    </span>
                </span>
            </button>
        </div>
        <button v-if="ranked.length > visibleLimit" type="button" class="family-category-more" :aria-expanded="expanded" @click="expanded = !expanded">
            {{ expanded ? '收起分类' : `展开全部 ${ranked.length} 项分类` }}
        </button>
    </div>
    <p v-else class="family-empty">所选期间暂无{{ isExpense ? '支出' : '收入' }}分类</p>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';

import { ChartValueType } from '@/core/chart.ts';
import type { ColorValue } from '@/core/color.ts';
import { useI18n } from '@/locales/helpers.ts';
import { familyMinorAmount, type FamilyCategoryRow } from '@/lib/family.ts';

const props = defineProps<{ rows: FamilyCategoryRow[]; palette: ColorValue[]; yearly: boolean; isExpense: boolean; mobile: boolean }>();
const emit = defineEmits<{ (e: 'select', id: string): void }>();
const { formatAmountToLocalizedNumeralsWithCurrency } = useI18n();
const visibleLimit = 6;
const otherId = 'remaining';
const expanded = ref(false);
const rotation = ref(0);
const rowList = ref<HTMLElement>();
const pieProps = props.mobile
    ? { showCenterText: true, centerTextBackground: 'var(--family-paper)', centerRadiusRatio: 0.62, directSliceClick: true }
    : { styleType: 'donut', compact: true };

const ranked = computed(() => props.rows.slice()
    .sort((left, right) => left.amount > right.amount ? -1 : left.amount < right.amount ? 1 : 0)
    .map((category, index) => ({ ...category, difference: category.amount - category.previous, color: props.palette[index % props.palette.length]! })));
const netTotal = computed(() => ranked.value.reduce((total, row) => total + row.amount, 0n));
const positive = computed(() => ranked.value.filter(row => row.amount > 0n));
const positiveTotal = computed(() => positive.value.reduce((total, row) => total + row.amount, 0n));
const remainingPositiveCount = computed(() => Math.max(0, positive.value.length - visibleLimit));
const negativeCount = computed(() => ranked.value.filter(row => row.amount < 0n).length);
const visibleRows = computed(() => expanded.value ? ranked.value : ranked.value.slice(0, visibleLimit));
const pieItems = computed(() => {
    const slices = positive.value.slice(0, visibleLimit).map(row => ({ id: row.id, name: row.name, value: familyMinorAmount(row.amount), color: row.color }));
    const remainder = positive.value.slice(visibleLimit).reduce((total, row) => total + row.amount, 0n);
    if (remainder > 0n) slices.push({ id: otherId, name: '其余分类', value: familyMinorAmount(remainder), color: '8c8c8c' });
    return slices;
});

let drag: { pointer: number; x: number; y: number; angle: number } | undefined;
let suppressClick = false;

function amount(value: bigint): string {
    return formatAmountToLocalizedNumeralsWithCurrency(familyMinorAmount(value), 'CNY');
}

function percent(value: bigint): string {
    const tenths = (value * 1000n + positiveTotal.value / 2n) / positiveTotal.value;
    return `${tenths / 10n}.${tenths % 10n}%`;
}

function openSlice(item: { id?: unknown }): void {
    if (suppressClick) return;
    if (item.id === otherId) {
        showRemaining();
    } else if (typeof item.id === 'string') {
        emit('select', item.id);
    }
}

function showRemaining(): void {
    expanded.value = true;
    void nextTick(() => rowList.value?.querySelectorAll('button')[visibleLimit]?.focus());
}

function pointerAngle(event: PointerEvent): number {
    const box = (event.currentTarget as HTMLElement).getBoundingClientRect();
    return Math.atan2(event.clientY - box.top - box.height / 2, event.clientX - box.left - box.width / 2) * 180 / Math.PI;
}

function startRotation(event: PointerEvent): void {
    if (!event.isPrimary || event.button !== 0) return;
    suppressClick = false;
    drag = { pointer: event.pointerId, x: event.clientX, y: event.clientY, angle: pointerAngle(event) };
}

function rotate(event: PointerEvent): void {
    if (drag?.pointer !== event.pointerId) return;
    if (!suppressClick && Math.hypot(event.clientX - drag.x, event.clientY - drag.y) < 8) return;
    if (!suppressClick) {
        suppressClick = true;
        (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
    }
    const angle = pointerAngle(event);
    rotation.value = (rotation.value + angle - drag.angle) % 360;
    drag.angle = angle;
}

function endRotation(event: PointerEvent): void {
    if (drag?.pointer === event.pointerId) drag = undefined;
}

function cancelDragClick(event: MouseEvent): void {
    if (!suppressClick) return;
    event.preventDefault();
    event.stopPropagation();
    suppressClick = false;
}
</script>
