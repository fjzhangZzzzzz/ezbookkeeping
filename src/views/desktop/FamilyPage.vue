<template>
    <main-page-layout no-navbar>
        <template #content>
            <family-content ref="family" :mobile="false" :dark-mode="theme.global.name.value === ThemeType.Dark" @open-personal="openPersonal" />
        </template>
    </main-page-layout>
    <edit-dialog ref="editor" :type="TransactionEditPageType.Transaction" />
    <snack-bar ref="snackbar" />
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { useTheme } from 'vuetify';

import FamilyContent from '@/components/common/FamilyContent.vue';
import EditDialog from '@/views/desktop/transactions/list/dialogs/EditDialog.vue';
import SnackBar from '@/components/desktop/SnackBar.vue';
import { ThemeType } from '@/core/theme.ts';
import { TransactionEditPageType } from '@/views/base/transactions/TransactionEditPageBase.ts';
import type { FamilyTransaction } from '@/lib/family.ts';

const theme = useTheme();
const family = ref<InstanceType<typeof FamilyContent>>();
const editor = ref<InstanceType<typeof EditDialog>>();
const snackbar = ref<InstanceType<typeof SnackBar>>();

async function openPersonal(transaction: FamilyTransaction): Promise<void> {
    try {
        await editor.value?.open({ id: transaction.id });
    } catch (error) {
        if (error) snackbar.value?.showError(error instanceof Error ? error : '无法打开本人交易');
    } finally {
        family.value?.resumePersonal();
    }
}
</script>
