<template>
    <component :is="group" v-bind="groupProps" role="group" :aria-label="label" @update:model-value="select">
        <component :is="button" v-for="option in options" :key="String(option.value)" v-bind="buttonProps"
                   :value="mobile ? undefined : option.value" :active="mobile && selection === option.value"
                   :aria-pressed="selection === option.value" @click="mobile && select(option.value)">
            {{ option.label }}
        </component>
    </component>
</template>

<script setup lang="ts" generic="T extends string | number | boolean">
import { computed, resolveComponent } from 'vue';

const props = defineProps<{ mobile: boolean; label: string; options: { label: string; value: T }[] }>();
const selection = defineModel<T>({ required: true });
const group = resolveComponent(props.mobile ? 'f7-segmented' : 'v-btn-toggle');
const button = resolveComponent(props.mobile ? 'f7-button' : 'v-btn');
const groupProps = computed(() => props.mobile ? { strong: true, round: true } : {
    class: 'segmented-button', mandatory: true, density: 'compact', modelValue: selection.value
});
const buttonProps = props.mobile ? { type: 'button', small: true, round: true } : { variant: 'text', color: 'default', ripple: false };

function select(value: T): void {
    if (props.options.some(option => option.value === value)) selection.value = value;
}
</script>
