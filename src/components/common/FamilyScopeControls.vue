<template>
    <div class="family-scope">
        <family-segmented-control v-model="yearly" :mobile="mobile" label="报表周期"
                                  :options="[{ label: '月', value: false }, { label: '年', value: true }]" />
        <div class="family-period" role="group" aria-label="期间">
            <component :is="button" v-bind="buttonProps" :disabled="!previous" :aria-label="scope.yearly ? '上一年' : '上个月'"
                       @click="shift(previous)">‹</component>
            <component :is="button" v-bind="buttonProps" aria-haspopup="dialog" @click="openPicker">{{ familyPeriodLabel(scope) }} ▾</component>
            <component :is="button" v-bind="buttonProps" :disabled="!next" :aria-label="scope.yearly ? '下一年' : '下个月'"
                       @click="shift(next)">›</component>
        </div>
        <div class="family-member-select">
            <span>成员</span>
            <component v-if="!mobile" :is="memberSelect" v-model="member" :items="memberOptions" density="compact"
                       hide-details aria-label="成员" :menu-props="menuContainer ? { attach: menuContainer } : undefined" />
            <component v-else :is="button" v-bind="buttonProps" outline :aria-label="`成员：${familyMemberLabel(member, members)}`"
                       aria-haspopup="dialog" @click="memberDialog?.showModal()">
                {{ familyMemberLabel(member, members) }} ▾
            </component>
        </div>
    </div>
    <dialog v-if="mobile" ref="memberDialog" class="family-dialog family-picker" aria-label="选择成员">
        <div class="family-dialog-header">
            <h2>选择成员</h2>
            <component :is="button" v-bind="buttonProps" @click="memberDialog?.close()">关闭</component>
        </div>
        <component :is="memberList" dividers class="family-member-options no-margin-vertical">
            <component :is="memberListItem" v-for="option in memberOptions" :key="option.value" link="#" no-chevron :title="option.title"
                       :aria-current="option.value === member ? 'true' : undefined" :class="{ 'list-item-selected': option.value === member }"
                       @click.prevent="chooseMember(option.value)">
                <template #content-start>
                    <component :is="memberIcon" class="list-item-checked-icon" f7="checkmark_alt" aria-hidden="true"
                               :style="{ visibility: option.value === member ? undefined : 'hidden' }" />
                </template>
            </component>
        </component>
    </dialog>
    <component :is="periodPicker" v-model:show="pickerOpen" :title="pickerTitle" :aria-label="pickerTitle"
               :model-value="month" :year-range="pickerYears" :year-only="scope.yearly" :container-el="menuContainer"
               @update:model-value="chooseMonth">
        <template #picker-controls>
            <form v-if="scope.yearly" class="family-year-jump" @submit.prevent="jumpToYear">
                <component :is="yearInput" v-bind="yearInputProps" inputmode="numeric" placeholder="1970–9998" aria-label="跳转年份"
                           @update:model-value="jumpYear = $event" @update:value="jumpYear = $event" @keydown.enter.prevent="jumpToYear" />
                <component :is="button" v-bind="buttonProps" :disabled="!validJumpYear" :aria-disabled="!validJumpYear" @click="jumpToYear">跳转年份</component>
            </form>
        </template>
    </component>
</template>

<script setup lang="ts">
import { computed, ref, resolveComponent } from 'vue';

import FamilySegmentedControl from '@/components/common/FamilySegmentedControl.vue';
import type { Year0BasedMonth } from '@/core/datetime.ts';
import { familyMemberLabel, familyPeriodLabel, familyShiftedMonth, type FamilyMember, type FamilyScope } from '@/lib/family.ts';

const props = defineProps<{ members: FamilyMember[]; mobile: boolean; menuContainer?: HTMLElement }>();
const scope = defineModel<FamilyScope>({ required: true });
const pickerOpen = ref(false);
const memberDialog = ref<HTMLDialogElement>();
const familyYears: [number, number] = [1970, 9998];
const yearWindow = ref(Number(scope.value.month.slice(0, 4)));
const jumpYear = ref('');
const visiblePastYears = props.mobile ? 6 : 12;
const pickerYears = computed<[number, number]>(() => scope.value.yearly
    ? [Math.max(familyYears[0], yearWindow.value - visiblePastYears), Math.min(familyYears[1], yearWindow.value + 24 - visiblePastYears)]
    : familyYears);
const validJumpYear = computed(() => /^\d{4}$/.test(jumpYear.value) && Number(jumpYear.value) >= familyYears[0] && Number(jumpYear.value) <= familyYears[1]);
const pickerTitle = computed(() => scope.value.yearly ? '选择年份' : '选择月份');
const periodPicker = resolveComponent(props.mobile ? 'month-selection-sheet' : 'month-selection-dialog');
const button = resolveComponent(props.mobile ? 'f7-button' : 'v-btn');
const memberSelect = props.mobile ? null : resolveComponent('v-select');
const memberList = props.mobile ? resolveComponent('f7-list') : null;
const memberListItem = props.mobile ? resolveComponent('f7-list-item') : null;
const memberIcon = props.mobile ? resolveComponent('f7-icon') : null;
const yearInput = resolveComponent(props.mobile ? 'f7-input' : 'v-text-field');
const yearInputProps = computed(() => props.mobile
    ? { type: 'text', outline: true, value: jumpYear.value }
    : { density: 'compact', hideDetails: true, modelValue: jumpYear.value });
const memberOptions = computed(() => [{ title: '全家', value: '' }, ...props.members.map(member => ({ title: member.nickname, value: member.id }))]);
const buttonProps = props.mobile ? { small: true, type: 'button' } : { variant: 'text', size: 'small' };
const previous = computed(() => familyShiftedMonth(scope.value, -1));
const next = computed(() => familyShiftedMonth(scope.value, 1));
const month = computed<Year0BasedMonth>(() => ({ year: Number(scope.value.month.slice(0, 4)), month0base: Number(scope.value.month.slice(5, 7)) - 1 }));
const member = computed({
    get: () => scope.value.member,
    set: (member: string) => {
        if (scope.value.member !== member) scope.value = { ...scope.value, member };
    }
});

function chooseMember(value: string): void {
    member.value = value;
    memberDialog.value?.close();
}

const yearly = computed({
    get: () => scope.value.yearly,
    set: (value: boolean) => {
        if (scope.value.yearly !== value) scope.value = { ...scope.value, yearly: value };
    }
});

function shift(value: string | null): void {
    if (value) {
        scope.value = { ...scope.value, month: value };
    }
}

function openPicker(): void {
    yearWindow.value = month.value.year;
    jumpYear.value = '';
    pickerOpen.value = true;
}

function jumpToYear(): void {
    if (validJumpYear.value) chooseMonth({ ...month.value, year: Number(jumpYear.value) });
}

function chooseMonth(value: Year0BasedMonth): void {
    shift(`${value.year}-${String(value.month0base + 1).padStart(2, '0')}`);
    pickerOpen.value = false;
}
</script>
