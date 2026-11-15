<template>
    <v-dialog width="800" :persistent="loading || submitting || isCategoryModified" v-model="showState">
        <one-column-dialog-layout :disabled="loading || submitting" :loading="loading"
                                  :title="tt(title)" :cancel-button-title="tt('Cancel')"
                                  @cancel="cancel">
            <template #content>
                <v-form class="mt-2">
                    <v-row>
                        <v-col cols="12" md="12">
                            <v-text-field
                                type="text"
                                autocomplete="off"
                                persistent-placeholder
                                :disabled="loading || submitting"
                                :label="tt('Category Name')"
                                :placeholder="tt('Category Name')"
                                v-model="category.name"
                            />
                        </v-col>
                        <v-col cols="12" md="12" v-if="editCategoryId && category.parentId && category.parentId !== '0'">
                            <v-select
                                item-title="name"
                                item-value="id"
                                persistent-placeholder
                                :disabled="loading || submitting"
                                :label="tt('Primary Category')"
                                :placeholder="tt('Primary Category')"
                                :items="allAvailableCategories"
                                :no-data-text="tt('No available primary category')"
                                v-model="category.parentId"
                            >
                                <template #item="{ props, internalItem }">
                                    <v-list-item v-bind="props">
                                        <template #prepend>
                                            <ItemIcon class="me-2" :icon-type="getCategoryIconType(internalItem.raw.iconType)"
                                                      :icon-id="internalItem.raw.icon" :color="internalItem.raw.color"></ItemIcon>
                                        </template>
                                        <template #title>
                                            <div class="text-truncate">{{ internalItem.raw.name }}</div>
                                        </template>
                                    </v-list-item>
                                </template>
                            </v-select>
                        </v-col>
                        <v-col cols="12" md="6">
                            <icon-select type="category"
                                         :all-system-icon-infos="ALL_CATEGORY_ICONS"
                                         :label="tt('Category Icon')"
                                         :color="category.color"
                                         :disabled="loading || submitting"
                                         v-model:icon-type="category.iconType"
                                         v-model="category.icon" />
                        </v-col>
                        <v-col cols="12" md="6">
                            <color-select :all-system-color-infos="ALL_CATEGORY_COLORS"
                                          :label="tt('Category Color')"
                                          :disabled="loading || submitting"
                                          v-model="category.color" />
                        </v-col>
                        <v-col cols="12" md="12">
                            <v-textarea
                                type="text"
                                autocomplete="off"
                                persistent-placeholder
                                rows="3"
                                :disabled="loading || submitting"
                                :label="tt('Description')"
                                :placeholder="tt('Your category description (optional)')"
                                v-model="category.comment"
                            />
                        </v-col>
                        <v-col class="py-0 my-n2" cols="12" md="12" v-if="editCategoryId">
                            <v-switch :disabled="loading || submitting"
                                      :label="tt('Visible')" v-model="category.visible"/>
                        </v-col>
                    </v-row>
                </v-form>
            </template>

            <template #footer>
                <v-spacer/>
                <v-tooltip :disabled="!inputIsEmpty" :text="inputEmptyProblemMessage ? tt(inputEmptyProblemMessage) : ''">
                    <template v-slot:activator="{ props }">
                        <div v-bind="props" class="d-inline-block">
                            <v-btn :disabled="inputIsEmpty || loading || submitting" @click="save">
                                {{ tt(saveButtonTitle) }}
                                <v-progress-circular indeterminate size="22" class="ms-2" v-if="submitting"></v-progress-circular>
                            </v-btn>
                        </div>
                    </template>
                </v-tooltip>
            </template>
        </one-column-dialog-layout>
    </v-dialog>

    <snack-bar ref="snackbar" />
</template>

<script setup lang="ts">
import SnackBar from '@/components/desktop/SnackBar.vue';

import { ref, computed, useTemplateRef } from 'vue';

import { useI18n } from '@/locales/helpers.ts';
import { useCategoryEditPageBase } from '@/views/base/categories/CategoryEditPageBase.ts';

import { useTransactionCategoriesStore } from '@/stores/transactionCategory.ts';

import type { ColorValue } from '@/core/color.ts';
import { CategoryType } from '@/core/category.ts';
import { ALL_CATEGORY_ICONS } from '@/consts/icon.ts';
import { ALL_CATEGORY_COLORS } from '@/consts/color.ts';
import { TransactionCategory } from '@/models/transaction_category.ts';

import { isEquals } from '@/lib/common.ts';
import { getCategoryIconType } from '@/lib/icon.ts';
import { generateRandomUUID } from '@/lib/misc.ts';
import services from '@/lib/services.ts';

interface TransactionCategoryEditResponse {
    message: string;
}

type SnackBarType = InstanceType<typeof SnackBar>;

const { tt } = useI18n();
const {
    editCategoryId,
    clientSessionId,
    loading,
    submitting,
    category,
    allAvailableCategories,
    title,
    saveButtonTitle,
    inputEmptyProblemMessage,
    inputIsEmpty
} = useCategoryEditPageBase();

const transactionCategoriesStore = useTransactionCategoriesStore();
const editRevision = ref<string | null>(transactionCategoriesStore.categoryRevision);

const snackbar = useTemplateRef<SnackBarType>('snackbar');

let resolveFunc: ((value: TransactionCategoryEditResponse) => void) | null = null;
let rejectFunc: ((reason?: unknown) => void) | null = null;

const showState = ref<boolean>(false);
const initCategory = ref<TransactionCategory | null>(null);

const isCategoryModified = computed<boolean>(() => {
    if (!editCategoryId.value) { // Add
        return !!initCategory.value && !isEquals(category.value.toCreateRequest(clientSessionId.value), initCategory.value.toCreateRequest(clientSessionId.value));
    } else { // Edit
        return !!initCategory.value && !isEquals(category.value.toModifyRequest(), initCategory.value.toModifyRequest());
    }
});

function open(options: { id?: string; parentId?: string; type?: CategoryType; currentCategory?: TransactionCategory, color?: ColorValue, icon?: string }): Promise<TransactionCategoryEditResponse> {
    editRevision.value = transactionCategoriesStore.categoryRevision;
    showState.value = true;
    loading.value = true;
    submitting.value = false;

    initCategory.value = TransactionCategory.createNewCategory();
    category.value.fillFrom(initCategory.value);

    if (options.id) {
        if (options.currentCategory) {
            category.value.fillFrom(options.currentCategory);
        }

        editCategoryId.value = options.id;
        transactionCategoriesStore.getCategory({
            categoryId: editCategoryId.value
        }).then(response => {
            category.value.fillFrom(response);
            initCategory.value = TransactionCategory.of(response);
            loading.value = false;
        }).catch(error => {
            loading.value = false;
            showState.value = false;

            if (!error.processed) {
                if (rejectFunc) {
                    rejectFunc(error);
                }
            }
        });
    } else if (options.parentId) {
        editCategoryId.value = null;

        const categoryType = options.type;

        if (categoryType !== CategoryType.Income &&
            categoryType !== CategoryType.Expense &&
            categoryType !== CategoryType.Transfer) {
            loading.value = false;
            showState.value = false;

            return Promise.reject('Parameter Invalid');
        }

        initCategory.value.type = categoryType;
        initCategory.value.parentId = options.parentId;

        category.value.type = initCategory.value.type;
        category.value.parentId = initCategory.value.parentId;

        if (options.color) {
            initCategory.value.color = options.color;
            category.value.color = initCategory.value.color;
        }

        if (options.icon) {
            initCategory.value.icon = options.icon;
            category.value.icon = initCategory.value.icon;
        }

        clientSessionId.value = generateRandomUUID();
        loading.value = false;
    }

    return new Promise((resolve, reject) => {
        resolveFunc = resolve;
        rejectFunc = reject;
    });
}

async function save(): Promise<void> {
    if (editCategoryId.value && initCategory.value &&
        (category.value.name !== initCategory.value.name || category.value.parentId !== initCategory.value.parentId || category.value.visible !== initCategory.value.visible)) {
        try {
            const impact = await services.getTransactionCategoryImpact(editCategoryId.value);
            if (!impact.data.success || !impact.data.result || impact.data.result.revision !== editRevision.value) {
                transactionCategoriesStore.updateTransactionCategoryListInvalidState(true);
                await transactionCategoriesStore.loadAllCategories({ force: false });
                editRevision.value = transactionCategoriesStore.categoryRevision;
                const current = transactionCategoriesStore.allTransactionCategoriesMap[editCategoryId.value];
                const parent = current?.parentId ? transactionCategoriesStore.allTransactionCategoriesMap[current.parentId] : undefined;
                window.alert(`分类已变化，当前名称：${current?.name ?? '已删除'}；当前上级：${parent?.name ?? '无'}。请对照草稿重新确认。`);
                return;
            }
            if (!window.confirm(`此修改将影响活跃成员的 ${impact.data.result.transactionCount} 笔历史交易。确认继续？`)) return;
        } catch {
            window.alert('无法获取影响范围，请稍后重试；草稿已保留。');
            return;
        }
    }
    submit();
}

function submit(): void {
    const problemMessage = inputEmptyProblemMessage.value;

    if (problemMessage) {
        snackbar.value?.showMessage(problemMessage);
        return;
    }

    submitting.value = true;

    transactionCategoriesStore.saveCategory({
        category: category.value,
        isEdit: !!editCategoryId.value,
        clientSessionId: clientSessionId.value,
        editRevision: editRevision.value ?? undefined
    }).then(() => {
        submitting.value = false;

        let message = 'You have saved this category';

        if (!editCategoryId.value) {
            message = 'You have added a new category';
        }

        resolveFunc?.({ message });
        showState.value = false;
    }).catch(error => {
        if (error?.message?.startsWith('分类已在其他设备修改')) {
            editRevision.value = transactionCategoriesStore.categoryRevision;
            const current = editCategoryId.value ? transactionCategoriesStore.allTransactionCategoriesMap[editCategoryId.value] : undefined;
            const parent = current?.parentId ? transactionCategoriesStore.allTransactionCategoriesMap[current.parentId] : undefined;
            window.alert(`分类已变化，当前名称：${current?.name ?? '已删除'}；当前上级：${parent?.name ?? '无'}。请对照草稿重新确认。`);
        }
        submitting.value = false;

        if (!error.processed) {
            snackbar.value?.showError(error);
        }
    });
}

function cancel(): void {
    rejectFunc?.();
    showState.value = false;
}

defineExpose({
    open
});
</script>
