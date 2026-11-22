<template>
    <v-dialog class="month-selection-dialog" width="480" :persistent="!!persistent" :attach="containerEl" v-model="showState">
        <one-column-dialog-layout content-class="pa-0"
                                  :title="title" :cancel-button-title="tt('Cancel')"
                                  @cancel="cancel">
            <template #toolbar>
                <v-btn v-if="!yearOnly" class="mx-2" density="comfortable" variant="outlined"
                       :disabled="!monthValue" @click="confirm">{{ tt('Apply') }}</v-btn>
            </template>

            <template #content>
                <div class="text-body-large text-wrap" v-if="hint">
                    <span>{{ hint }}</span>
                    <slot></slot>
                </div>
                <slot name="picker-controls"></slot>
                <v-row>
                    <v-col>
                        <month-picker :is-dark-mode="isDarkMode" :year-range="yearRange" :year-only="yearOnly"
                                      :model-value="monthValue" @update:model-value="selectPeriod"></month-picker>
                    </v-col>
                </v-row>
            </template>
        </one-column-dialog-layout>
    </v-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { useTheme } from 'vuetify';

import { useI18n } from '@/locales/helpers.ts';

import { ThemeType } from '@/core/theme.ts';
import type { Year0BasedMonth } from '@/core/datetime.ts';

import { getYear0BasedMonthObjectFromUnixTime, getThisMonthFirstUnixTime } from '@/lib/datetime.ts';

const props = defineProps<{
    modelValue?: Year0BasedMonth;
    title?: string;
    hint?: string;
    show: boolean;
    persistent?: boolean;
    yearOnly?: boolean;
    yearRange?: [number, number];
    containerEl?: HTMLElement;
}>();

const emit = defineEmits<{
    (e: 'update:modelValue', value: Year0BasedMonth): void;
    (e: 'update:show', value: boolean): void;
    (e: 'error', message: string): void;
}>();

const theme = useTheme();

const { tt } = useI18n();

const monthValue = ref<Year0BasedMonth>(getYear0BasedMonthObjectFromUnixTime(getThisMonthFirstUnixTime()));

const isDarkMode = computed<boolean>(() => theme.global.name.value === ThemeType.Dark);
const showState = computed<boolean>({
    get: () => props.show || false,
    set: (value) => emit('update:show', value)
});

function confirm(): void {
    if (monthValue.value.year <= 0 || monthValue.value.month0base < 0) {
        emit('error', 'Date is too early');
        return;
    }

    emit('update:modelValue', monthValue.value);
}

function selectPeriod(value: Year0BasedMonth | Year0BasedMonth[]): void {
    if (Array.isArray(value)) return;
    monthValue.value = value;
    if (props.yearOnly) confirm();
}

function cancel(): void {
    emit('update:show', false);
}

watch(() => props.modelValue, (newValue) => {
    if (newValue) {
        monthValue.value = newValue;
    }
});

watch(() => props.show, (newValue) => {
    if (newValue && props.modelValue) {
        monthValue.value = props.modelValue;
    }
});
</script>

<style>
.month-selection-dialog .dp--main {
    .dp--menu {
        border: 0;
        padding-inline-start: 3px;
    }

    .dp--instance-calendar .dp--overlay.dp--overlay-relative {
        width: 100% !important;
    }
}
</style>
