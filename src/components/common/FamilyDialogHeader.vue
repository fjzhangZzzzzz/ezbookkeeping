<template>
    <div class="family-dialog-header">
        <component v-if="backLabel" :is="button" class="family-dialog-icon" v-bind="backProps"
                   :aria-label="backLabel" :title="backLabel" @click="emit('back')" />
        <div>
            <h2 :id="titleId">{{ title }}</h2>
            <p v-if="subtitle" class="family-note">{{ subtitle }}</p>
        </div>
        <component :is="button" class="family-dialog-icon" v-bind="closeProps"
                   :aria-label="closeLabel" :title="closeLabel" @click="emit('close')" />
    </div>
</template>

<script setup lang="ts">
import { resolveComponent } from 'vue';
import { mdiArrowLeft, mdiClose } from '@mdi/js';

const props = withDefaults(defineProps<{ mobile: boolean; title: string; titleId?: string; subtitle?: string; backLabel?: string; closeLabel?: string }>(), { closeLabel: '关闭' });
const emit = defineEmits<{ (event: 'back' | 'close'): void }>();
const button = resolveComponent(props.mobile ? 'f7-button' : 'v-btn');
const closeProps = props.mobile ? { type: 'button', iconF7: 'xmark' } : { icon: mdiClose, variant: 'text', density: 'comfortable' };
const backProps = props.mobile ? { type: 'button', iconF7: 'chevron_left' } : { icon: mdiArrowLeft, variant: 'text', density: 'comfortable' };
</script>
