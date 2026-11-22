<template>
    <f7-page @page:afterin="family?.resumePersonal()">
        <f7-navbar title="家庭账本" back-link="返回">
            <f7-nav-right>
                <f7-button type="button" icon-f7="ellipsis" popover-open=".family-actions-menu" aria-label="更多家庭操作" />
            </f7-nav-right>
        </f7-navbar>
        <family-content ref="family" mobile :dark-mode="!!environments.framework7DarkMode" @open-personal="openPersonal" />
    </f7-page>
</template>

<script setup lang="ts">
import { defineAsyncComponent, ref } from 'vue';
import type { Router } from 'framework7/types';

import { useEnvironmentsStore } from '@/stores/environment.ts';
import { TransactionType } from '@/core/transaction.ts';
import type { FamilyTransaction } from '@/lib/family.ts';
import type FamilyContentType from '@/components/common/FamilyContent.vue';

const FamilyContent = defineAsyncComponent(() => import('@/components/common/FamilyContent.vue'));
const props = defineProps<{ f7router: Router.Router }>();
const family = ref<InstanceType<typeof FamilyContentType>>();
const environments = useEnvironmentsStore();

function openPersonal(transaction: FamilyTransaction): void {
    props.f7router.navigate(`/transaction/detail?id=${encodeURIComponent(transaction.id)}&type=${Math.min(transaction.type, TransactionType.Transfer)}`);
}
</script>
