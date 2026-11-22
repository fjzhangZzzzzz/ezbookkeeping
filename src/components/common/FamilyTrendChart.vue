<template>
    <axis-chart :class="expanded ? 'family-trend-expanded' : 'family-trend'"
                :type="line ? 'line' : 'column'" :dark-mode="darkMode" :hide-legend="items.length === 1"
                :all-category-names="labels" :items="items" :category-type-name="yearly ? '月份' : '日期'"
                :value-type="ChartValueType.Amount" :sorting-type="ChartSortingType.DisplayOrder.type"
                default-currency="CNY" show-value use-custom-color no-animation />
</template>

<script lang="ts">
import { use } from 'echarts/core';
import { BarChart, LineChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';

use([BarChart, LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer]);
</script>

<script setup lang="ts">
import AxisChart from '@/components/desktop/AxisChart.vue';
import { ChartValueType, type AxisChartSourceDataItem } from '@/core/chart.ts';
import { ChartSortingType } from '@/core/statistics.ts';

defineProps<{
    items: AxisChartSourceDataItem[];
    labels: string[];
    yearly: boolean;
    line: boolean;
    darkMode: boolean;
    expanded?: boolean;
}>();
</script>

<style scoped>
.family-trend { width: 100%; height: 240px; }
.family-trend-expanded { width: 100%; height: min(62dvh, 560px); }
</style>
