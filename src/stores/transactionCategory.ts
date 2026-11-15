import { ref, computed } from 'vue';
import { defineStore } from 'pinia';
import sha256 from 'crypto-js/sha256';

import type { BeforeResolveFunction } from '@/core/base.ts';

import { itemAndIndex, values } from '@/core/base.ts';
import { CategoryType } from '@/core/category.ts';

import {
    type TransactionCategoryInfoResponse,
    type TransactionCategoryCreateBatchRequest,
    type TransactionCategoryNewDisplayOrderRequest,
    TransactionCategory,
} from '@/models/transaction_category.ts';

import { isEquals } from '@/lib/common.ts';
import { hasAnyVisibleCategory } from '@/lib/category.ts';
import services, { type ApiResponsePromise } from '@/lib/services.ts';
import logger from '@/lib/logger.ts';

export const useTransactionCategoriesStore = defineStore('transactionCategories', () =>{
    let loadingPromise: Promise<Record<number, TransactionCategory[]>> | null = null;

    const allTransactionCategories = ref<Record<number, TransactionCategory[]>>({});
    const allTransactionCategoriesMap = ref<Record<string, TransactionCategory>>({});
    const transactionCategoryListStateInvalid = ref<boolean>(true);
    const categoryRevision = ref<string | null>(null);
    function unknownCreateKey(): string {
        const userData = localStorage.getItem('ebk_user_info');
        let username = '';
        try { username = userData ? JSON.parse(userData).username || '' : ''; } catch { /* Fail closed using a shared fallback key. */ }
        return 'ebk_category_create_unknown_' + sha256(username).toString();
    }

    let unknownCreateInMemory = false;

    function allowCategoryCreate(): boolean {
        try {
            const key = unknownCreateKey();
            // Refuse to issue a write if a later unknown result cannot be recorded.
            const probe = key + '_probe';
            localStorage.setItem(probe, '1');
            localStorage.removeItem(probe);
            if (!unknownCreateInMemory && !localStorage.getItem(key)) return true;
            if (!window.confirm('上次创建结果未知。请先查看最新分类列表，核对是否已创建；确认已经核对并解除阻止？')) return false;
            localStorage.removeItem(key);
            unknownCreateInMemory = false;
            return true;
        } catch {
            return false;
        }
    }

    function markCategoryCreateUnknown(): boolean {
        try {
            localStorage.setItem(unknownCreateKey(), '1');
            unknownCreateInMemory = true;
            return true;
        } catch {
            return false;
        }
    }

    function clearCategoryCreateUnknown(): void {
        try {
            localStorage.removeItem(unknownCreateKey());
            unknownCreateInMemory = false;
        } catch { /* Keep the conservative in-memory block. */ }
    }

    function validCreatedCategoryId(value: unknown): boolean {
        return typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value) && (value.length < 19 || value <= '9223372036854775807');
    }

    function validBatchCategoryResult(value: unknown, request: TransactionCategoryCreateBatchRequest): boolean {
        if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
        const ids = new Set<string>();
        let primaryCount = 0;
        let secondaryCount = 0;
        for (const [type, categories] of Object.entries(value)) {
            if (!['1', '2', '3'].includes(type) || !Array.isArray(categories)) return false;
            for (const category of categories) {
                if (!category || !validCreatedCategoryId(category.id) || ids.has(category.id)) return false;
                ids.add(category.id);
                primaryCount++;
                if (category.subCategories !== undefined) {
                    if (!Array.isArray(category.subCategories)) return false;
                    for (const child of category.subCategories) {
                        if (!child || !validCreatedCategoryId(child.id) || ids.has(child.id)) return false;
                        ids.add(child.id);
                        secondaryCount++;
                    }
                }
            }
        }
        return primaryCount === request.categories.length &&
            secondaryCount === request.categories.reduce((sum, category) => sum + category.subCategories.length, 0);
    }

    async function consistentCategoryList() {
        for (let attempt = 0; attempt < 3; attempt++) {
            let before;
            try {
                before = await services.getTransactionCategoryRevision();
            } catch (error: unknown) {
                const status = (error as { response?: { status?: number } })?.response?.status;
                if (status !== 404) throw error;
                const list = await services.getAllTransactionCategories();
                return { list, revision: null };
            }
            const list = await services.getAllTransactionCategories();
            const after = await services.getTransactionCategoryRevision();
            if (before.data.success && after.data.success && list.data.success && list.data.result && before.data.result.revision === after.data.result.revision) {
                return { list, revision: before.data.result.revision };
            }
        }
        throw { message: '分类正在变更，请稍后重试' };
    }

    async function previewImpact(categoryId: string): Promise<{ revision: string, transactionCount: string }> {
        const response = await services.getTransactionCategoryImpact(categoryId);
        if (!response.data.success || !response.data.result) throw { message: '无法获取分类影响范围' };
        const impact = response.data.result;
        if (categoryRevision.value === null || impact.revision !== categoryRevision.value) {
            transactionCategoryListStateInvalid.value = true;
            try { await loadAllCategories({ force: false }); } catch { /* Keep state invalid on failure. */ }
            throw { message: '分类已变化，请检查最新分类后重新操作' };
        }
        return impact;
    }

    function expectedRevision(): string {
        if (categoryRevision.value === null) {
            throw { message: '请先刷新分类后重试' };
        }
        return categoryRevision.value;
    }

    async function refreshAfterWrite(): Promise<void> {
        if (loadingPromise) {
            try { await loadingPromise; } catch { /* Fetch a new snapshot below. */ }
        }
        transactionCategoryListStateInvalid.value = true;
        categoryRevision.value = null;
        try {
            await loadAllCategories({ force: false });
        } catch {
            transactionCategoryListStateInvalid.value = true;
            throw { message: '分类已保存，但刷新失败；请勿重复提交，请稍后刷新分类列表' };
        }
    }

    async function onWriteError(error: { response?: { data?: { errorCode?: number } } }): Promise<never> {
        if (error?.response?.data?.errorCode === 206013) {
            if (loadingPromise) {
                try { await loadingPromise; } catch { /* Fetch a new snapshot below. */ }
            }
            transactionCategoryListStateInvalid.value = true;
            categoryRevision.value = null;
            try { await loadAllCategories({ force: false }); } catch { /* Keep the draft and surface the conflict. */ }
            throw { message: '分类已在其他设备修改，请检查最新分类并重新确认；草稿已保留' };
        }
        throw error;
    }

    const allAvailablePrimaryCategoriesCount = computed<number>(() => {
        let count = 0;

        for (const categories of values(allTransactionCategories.value)) {
            count += categories.length;
        }

        return count;
    });

    const allAvailableSecondaryCategoriesCount = computed<number>(() => {
        let count = 0;

        for (const categories of values(allTransactionCategories.value)) {
            for (const category of categories) {
                if (category.subCategories) {
                    count += category.subCategories.length;
                }
            }
        }

        return count;
    });

    const hasVisibleExpenseCategories = computed<boolean>(() => {
        if (!allTransactionCategories.value || !allTransactionCategories.value[CategoryType.Expense] || !allTransactionCategories.value[CategoryType.Expense].length) {
            return false;
        }

        return hasAnyVisibleCategory(allTransactionCategories.value[CategoryType.Expense]);
    });

    const hasVisibleIncomeCategories = computed<boolean>(() => {
        if (!allTransactionCategories.value || !allTransactionCategories.value[CategoryType.Income] || !allTransactionCategories.value[CategoryType.Income].length) {
            return false;
        }

        return hasAnyVisibleCategory(allTransactionCategories.value[CategoryType.Income]);
    });

    const hasVisibleTransferCategories = computed<boolean>(() => {
        if (!allTransactionCategories.value || !allTransactionCategories.value[CategoryType.Transfer] || !allTransactionCategories.value[CategoryType.Transfer].length) {
            return false;
        }

        return hasAnyVisibleCategory(allTransactionCategories.value[CategoryType.Transfer]);
    });

    function loadTransactionCategoryList(allCategories: Record<number, TransactionCategory[]>): void {
        allTransactionCategories.value = allCategories;
        allTransactionCategoriesMap.value = {};

        for (const categories of values(allCategories)) {
            for (const category of categories) {
                allTransactionCategoriesMap.value[category.id] = category;

                if (!category.subCategories) {
                    continue;
                }

                for (const subCategory of category.subCategories) {
                    allTransactionCategoriesMap.value[subCategory.id] = subCategory;
                }
            }
        }
    }

    function addCategoryToTransactionCategoryList(category: TransactionCategory): void {
        let categoryList: TransactionCategory[] | undefined = undefined;

        if (!category.parentId || category.parentId === '0') {
            categoryList = allTransactionCategories.value[category.type];
        } else if (allTransactionCategoriesMap.value[category.parentId]) {
            categoryList = allTransactionCategoriesMap.value[category.parentId]!.subCategories;
        }

        if (categoryList) {
            categoryList.push(category);
        }

        allTransactionCategoriesMap.value[category.id] = category;
    }

    function updateCategoryInTransactionCategoryList(currentCategory: TransactionCategory, oldCategory?: TransactionCategory): boolean {
        if (oldCategory && currentCategory.parentId !== oldCategory.parentId) {
            return false;
        }

        let categoryList: TransactionCategory[] | undefined = undefined;

        if (!currentCategory.parentId || currentCategory.parentId === '0') {
            categoryList = allTransactionCategories.value[currentCategory.type];
        } else if (allTransactionCategoriesMap.value[currentCategory.parentId]) {
            categoryList = allTransactionCategoriesMap.value[currentCategory.parentId]!.subCategories;
        }

        if (categoryList) {
            for (const [category, index] of itemAndIndex(categoryList)) {
                if (category.id === currentCategory.id) {
                    if (!currentCategory.parentId || currentCategory.parentId === '0') {
                        currentCategory.subCategories = category.subCategories;
                    }

                    categoryList.splice(index, 1, currentCategory);
                    break;
                }
            }
        }

        allTransactionCategoriesMap.value[currentCategory.id] = currentCategory;
        return true;
    }

    function updateCategoryDisplayOrderInCategoryList({ category, from, to }: { category: TransactionCategory, from: number, to: number }): void {
        let categoryList: TransactionCategory[] | undefined = undefined;

        if (!category.parentId || category.parentId === '0') {
            categoryList = allTransactionCategories.value[category.type];
        } else if (allTransactionCategoriesMap.value[category.parentId]) {
            categoryList = allTransactionCategoriesMap.value[category.parentId]!.subCategories;
        }

        if (categoryList) {
            categoryList.splice(to, 0, categoryList.splice(from, 1)[0] as TransactionCategory);
        }
    }

    function updateCategoryVisibilityInTransactionCategoryList({ category, hidden }: { category: TransactionCategory, hidden: boolean }): void {
        if (allTransactionCategoriesMap.value[category.id]) {
            allTransactionCategoriesMap.value[category.id]!.visible = !hidden;
        }
    }

    function removeCategoryFromTransactionCategoryList(currentCategory: TransactionCategory): void {
        let categoryList: TransactionCategory[] | undefined = undefined;

        if (!currentCategory.parentId || currentCategory.parentId === '0') {
            categoryList = allTransactionCategories.value[currentCategory.type];
        } else if (allTransactionCategoriesMap.value[currentCategory.parentId]) {
            categoryList = allTransactionCategoriesMap.value[currentCategory.parentId]!.subCategories;
        }

        if (categoryList) {
            for (const [category, index] of itemAndIndex(categoryList)) {
                if (category.id === currentCategory.id) {
                    categoryList.splice(index, 1);
                    break;
                }
            }
        }

        if (allTransactionCategoriesMap.value[currentCategory.id] && allTransactionCategoriesMap.value[currentCategory.id]!.subCategories) {
            const subCategoryList = allTransactionCategoriesMap.value[currentCategory.id]!.subCategories;

            if (subCategoryList) {
                for (const subCategory of subCategoryList) {
                    if (allTransactionCategoriesMap.value[subCategory.id]) {
                        delete allTransactionCategoriesMap.value[subCategory.id];
                    }
                }
            }
        }

        if (allTransactionCategoriesMap.value[currentCategory.id]) {
            delete allTransactionCategoriesMap.value[currentCategory.id];
        }
    }

    function updateTransactionCategoryListInvalidState(invalidState: boolean): void {
        transactionCategoryListStateInvalid.value = invalidState;
    }

    function resetTransactionCategories(): void {
        loadingPromise = null;
        categoryRevision.value = null;
        allTransactionCategories.value = {};
        allTransactionCategoriesMap.value = {};
        transactionCategoryListStateInvalid.value = true;
    }

    function loadAllCategories({ force }: { force?: boolean }): Promise<Record<number, TransactionCategory[]>> {
        if (loadingPromise) {
            return loadingPromise;
        }

        if (!force && !transactionCategoryListStateInvalid.value) {
            return new Promise((resolve) => {
                resolve(allTransactionCategories.value);
            });
        }

        const currentPromise = loadingPromise = new Promise((resolve, reject) => {
            consistentCategoryList().then(({ list: response, revision }) => {
                if (!loadingPromise || loadingPromise !== currentPromise) {
                    logger.error('loadingPromise is invalid after retrieving category list');
                    reject({ message: 'An error occurred' });
                    return;
                }

                const data = response.data;
                categoryRevision.value = revision;

                if (!data || !data.success || !data.result) {
                    loadingPromise = null;
                    reject({ message: 'Unable to retrieve category list' });
                    return;
                }

                if (!data.result[CategoryType.Income]) {
                    data.result[CategoryType.Income] = [];
                }

                if (!data.result[CategoryType.Expense]) {
                    data.result[CategoryType.Expense] = [];
                }

                if (!data.result[CategoryType.Transfer]) {
                    data.result[CategoryType.Transfer] = [];
                }

                if (transactionCategoryListStateInvalid.value) {
                    updateTransactionCategoryListInvalidState(false);
                }

                const transactionCategories = TransactionCategory.ofMap(data.result);

                if (force && data.result && isEquals(allTransactionCategories.value, transactionCategories)) {
                    loadingPromise = null;
                    reject({ message: 'Category list is up to date', isUpToDate: true });
                    return;
                }

                loadTransactionCategoryList(transactionCategories);
                loadingPromise = null;

                resolve(transactionCategories);
            }).catch(error => {
                if (loadingPromise === currentPromise) {
                    loadingPromise = null;
                }

                if (force) {
                    logger.error('failed to force load category list', error);
                } else {
                    logger.error('failed to load category list', error);
                }

                if (error.response && error.response.data && error.response.data.errorMessage) {
                    reject({ error: error.response.data });
                } else if (!error.processed) {
                    reject({ message: 'Unable to retrieve category list' });
                } else {
                    reject(error);
                }
            });
        });

        return loadingPromise;
    }

    function getCategory({ categoryId }: { categoryId: string }): Promise<TransactionCategory> {
        return new Promise((resolve, reject) => {
            services.getTransactionCategory({
                id: categoryId
            }).then(response => {
                const data = response.data;

                if (!data || !data.success || !data.result) {
                    reject({ message: 'Unable to retrieve category' });
                    return;
                }

                const transactionCategory = TransactionCategory.of(data.result);

                resolve(transactionCategory);
            }).catch(error => {
                logger.error('failed to load category info', error);

                if (error.response && error.response.data && error.response.data.errorMessage) {
                    reject({ error: error.response.data });
                } else if (!error.processed) {
                    reject({ message: 'Unable to retrieve category' });
                } else {
                    reject(error);
                }
            });
        });
    }

    function saveCategory({ category, isEdit, clientSessionId, editRevision }: { category: TransactionCategory, isEdit: boolean, clientSessionId: string, editRevision?: string }): Promise<TransactionCategory> {
        return new Promise((resolve, reject) => {
            let promise: ApiResponsePromise<TransactionCategoryInfoResponse>;

            if (!isEdit && !allowCategoryCreate()) {
                reject({ message: '请先核对分类列表，再明确解除创建阻止' });
                return;
            }
            if (editRevision === undefined) {
                reject({ message: '请刷新分类后重新打开编辑页面；草稿已保留' });
                return;
            }

            if (!isEdit) {
                if (!markCategoryCreateUnknown()) { reject({ message: '无法安全记录待核实状态，未发送请求' }); return; }
                promise = services.addTransactionCategory({ ...category.toCreateRequest(clientSessionId), expectedRevision: editRevision });
            } else {
                promise = services.modifyTransactionCategory({ ...category.toModifyRequest(), expectedRevision: editRevision });
            }

            promise.then(response => {
                const data = response.data;

                if (!data || !data.success || !data.result) {
                    if (!isEdit) {
                        reject({ message: '分类创建结果未知，请核对分类列表后再操作' });
                    } else {
                        reject({ message: '分类保存结果未知，请核对分类列表后再操作' });
                    }
                    return;
                }

                if (!validCreatedCategoryId(data.result.id)) {
                    reject({ message: '分类保存结果未知，请核对分类列表后再操作' });
                    return;
                }
                const transactionCategory = TransactionCategory.of(data.result);
                if (!isEdit) clearCategoryCreateUnknown();

                if (!isEdit) {
                    addCategoryToTransactionCategoryList(transactionCategory);
                } else {
                    const result = updateCategoryInTransactionCategoryList(transactionCategory, allTransactionCategoriesMap.value[category.id]);

                    if (!result && !transactionCategoryListStateInvalid.value) {
                        updateTransactionCategoryListInvalidState(true);
                    }
                }

                refreshAfterWrite().then(() => resolve(transactionCategory)).catch(reject);
            }).catch(error => {
                if (!isEdit && !(error?.response?.status === 409 && error.response.data?.errorCode === 206013)) {
                    reject({ message: '创建结果未知。请先核对分类列表，再明确解除创建阻止' });
                    return;
                }
                if (!isEdit && error?.response?.status === 409 && error.response.data?.errorCode === 206013) clearCategoryCreateUnknown();
                if (error?.response?.data?.errorCode === 206013) { onWriteError(error).catch(reject); return; }
                logger.error('failed to save category', error);

                if (error.response && error.response.data && error.response.data.errorMessage) {
                    reject({ error: error.response.data });
                } else if (!error.processed) {
                    if (!isEdit) {
                        reject({ message: 'Unable to add category' });
                    } else {
                        reject({ message: 'Unable to save category' });
                    }
                } else {
                    reject(error);
                }
            });
        });
    }

    function addCategories(req: TransactionCategoryCreateBatchRequest): Promise<Record<number, TransactionCategory[]>> {
        return new Promise((resolve, reject) => {
            if (!allowCategoryCreate()) { reject({ message: '请先核对分类列表，再明确解除创建阻止' }); return; }
            if (!markCategoryCreateUnknown()) { reject({ message: '无法安全记录待核实状态，未发送请求' }); return; }
            services.addTransactionCategoryBatch({ ...req, expectedRevision: expectedRevision() }).then(response => {
                const data = response.data;

                if (!data || !data.success || !data.result) {
                    reject({ message: '批量创建结果未知，请核对分类列表后再操作' });
                    return;
                }

                if (!validBatchCategoryResult(data.result, req)) {
                    reject({ message: '批量创建结果未知，请核对分类列表后再操作' });
                    return;
                }

                if (!transactionCategoryListStateInvalid.value) {
                    updateTransactionCategoryListInvalidState(true);
                }

                const transactionCategories = TransactionCategory.ofMap(data.result);
                clearCategoryCreateUnknown();

                refreshAfterWrite().then(() => resolve(transactionCategories)).catch(reject);
            }).catch(error => {
                if (!(error?.response?.status === 409 && error.response.data?.errorCode === 206013)) {
                    reject({ message: '创建结果未知。请先核对分类列表，再明确解除创建阻止' });
                    return;
                }
                if (error?.response?.status === 409 && error.response.data?.errorCode === 206013) clearCategoryCreateUnknown();
                if (error?.response?.data?.errorCode === 206013) { onWriteError(error).catch(reject); return; }
                logger.error('failed to add categories', error);

                if (error.response && error.response.data && error.response.data.errorMessage) {
                    reject({ error: error.response.data });
                } else if (!error.processed) {
                    reject({ message: 'Unable to add category' });
                } else {
                    reject(error);
                }
            });
        });
    }

    function addPresetCategories(req: TransactionCategoryCreateBatchRequest): Promise<Record<number, TransactionCategory[]>> {
        return new Promise((resolve, reject) => {
            if (!allowCategoryCreate()) { reject({ message: '请先核对分类列表，再明确解除创建阻止' }); return; }
            if (!markCategoryCreateUnknown()) { reject({ message: '无法安全记录待核实状态，未发送请求' }); return; }
            services.addTransactionCategoryBatch({ ...req, expectedRevision: expectedRevision() }).then(response => {
                const data = response.data;

                if (!data || !data.success || !data.result) {
                    reject({ message: '批量创建结果未知，请核对分类列表后再操作' });
                    return;
                }

                if (!validBatchCategoryResult(data.result, req)) {
                    reject({ message: '批量创建结果未知，请核对分类列表后再操作' });
                    return;
                }

                if (!transactionCategoryListStateInvalid.value) {
                    updateTransactionCategoryListInvalidState(true);
                }

                const transactionCategories = TransactionCategory.ofMap(data.result);
                clearCategoryCreateUnknown();

                refreshAfterWrite().then(() => resolve(transactionCategories)).catch(reject);
            }).catch(error => {
                if (!(error?.response?.status === 409 && error.response.data?.errorCode === 206013)) {
                    reject({ message: '创建结果未知。请先核对分类列表，再明确解除创建阻止' });
                    return;
                }
                if (error?.response?.status === 409 && error.response.data?.errorCode === 206013) clearCategoryCreateUnknown();
                if (error?.response?.data?.errorCode === 206013) { onWriteError(error).catch(reject); return; }
                logger.error('failed to add preset categories', error);

                if (error.response && error.response.data && error.response.data.errorMessage) {
                    reject({ error: error.response.data });
                } else if (!error.processed) {
                    reject({ message: 'Unable to add preset categories' });
                } else {
                    reject(error);
                }
            });
        });
    }

    function changeCategoryDisplayOrder({ categoryId, from, to }: { categoryId: string, from: number, to: number }): Promise<void> {
        const category = allTransactionCategoriesMap.value[categoryId];

        return new Promise((resolve, reject) => {
            if (!category) {
                reject({ message: 'Unable to move category' });
                return;
            }

            if (!category.parentId || category.parentId === '0') {
                if (!allTransactionCategories.value[category.type] ||
                    !allTransactionCategories.value[category.type]![to]) {
                    reject({ message: 'Unable to move category' });
                    return;
                }
            } else {
                const subCategoryList = allTransactionCategoriesMap.value[category.parentId]?.subCategories;

                if (!subCategoryList || !subCategoryList[to]) {
                    reject({ message: 'Unable to move category' });
                    return;
                }
            }

            if (!transactionCategoryListStateInvalid.value) {
                updateTransactionCategoryListInvalidState(true);
            }

            updateCategoryDisplayOrderInCategoryList({ category, from, to });

            resolve();
        });
    }

    function updateCategoryDisplayOrders({ type, parentId }: { type: CategoryType, parentId: string }): Promise<boolean> {
        const newDisplayOrders: TransactionCategoryNewDisplayOrderRequest[] = [];

        let categoryList: TransactionCategory[] | undefined = undefined;

        if (!parentId || parentId === '0') {
            categoryList = allTransactionCategories.value[type];
        } else if (allTransactionCategoriesMap.value[parentId]) {
            categoryList = allTransactionCategoriesMap.value[parentId].subCategories;
        }

        if (categoryList) {
            for (const [category, index] of itemAndIndex(categoryList)) {
                newDisplayOrders.push({
                    id: category.id,
                    displayOrder: index + 1
                });
            }
        }

        return new Promise((resolve, reject) => {
            services.moveTransactionCategory({
                newDisplayOrders: newDisplayOrders,
                expectedRevision: expectedRevision()
            }).then(response => {
                const data = response.data;

                if (!data || !data.success || !data.result) {
                    reject({ message: 'Unable to move category' });
                    return;
                }

                refreshAfterWrite().then(() => resolve(data.result)).catch(reject);
            }).catch(error => {
                if (error?.response?.data?.errorCode === 206013) { onWriteError(error).catch(reject); return; }
                logger.error('failed to save categories display order', error);

                if (error.response && error.response.data && error.response.data.errorMessage) {
                    reject({ error: error.response.data });
                } else if (!error.processed) {
                    reject({ message: 'Unable to move category' });
                } else {
                    reject(error);
                }
            });
        });
    }

    function hideCategory({ category, hidden, revision }: { category: TransactionCategory, hidden: boolean, revision: string }): Promise<boolean> {
        return new Promise((resolve, reject) => {
            services.hideTransactionCategory({
                id: category.id,
                hidden: hidden,
                expectedRevision: revision
            }).then(response => {
                const data = response.data;

                if (!data || !data.success || !data.result) {
                    if (hidden) {
                        reject({ message: 'Unable to hide this category' });
                    } else {
                        reject({ message: 'Unable to unhide this category' });
                    }

                    return;
                }

                updateCategoryVisibilityInTransactionCategoryList({ category, hidden });

                refreshAfterWrite().then(() => resolve(data.result)).catch(reject);
            }).catch(error => {
                if (error?.response?.data?.errorCode === 206013) { onWriteError(error).catch(reject); return; }
                logger.error('failed to change category visibility', error);

                if (error.response && error.response.data && error.response.data.errorMessage) {
                    reject({ error: error.response.data });
                } else if (!error.processed) {
                    if (hidden) {
                        reject({ message: 'Unable to hide this category' });
                    } else {
                        reject({ message: 'Unable to unhide this category' });
                    }
                } else {
                    reject(error);
                }
            });
        });
    }

    function deleteCategory({ category, beforeResolve, revision }: { category: TransactionCategory, beforeResolve?: BeforeResolveFunction, revision: string }): Promise<boolean> {
        return new Promise((resolve, reject) => {
            services.deleteTransactionCategory({
                id: category.id,
                expectedRevision: revision
            }).then(response => {
                const data = response.data;

                if (!data || !data.success || !data.result) {
                    reject({ message: 'Unable to delete this category' });
                    return;
                }

                if (beforeResolve) {
                    beforeResolve(() => {
                        removeCategoryFromTransactionCategoryList(category);
                    });
                } else {
                    removeCategoryFromTransactionCategoryList(category);
                }

                refreshAfterWrite().then(() => resolve(data.result)).catch(reject);
            }).catch(error => {
                if (error?.response?.data?.errorCode === 206013) { onWriteError(error).catch(reject); return; }
                logger.error('failed to delete category', error);

                if (error.response && error.response.data && error.response.data.errorMessage) {
                    reject({ error: error.response.data });
                } else if (!error.processed) {
                    reject({ message: 'Unable to delete this category' });
                } else {
                    reject(error);
                }
            });
        });
    }

    if (typeof document !== 'undefined') {
        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'visible' && categoryRevision.value !== null && !transactionCategoryListStateInvalid.value) {
                transactionCategoryListStateInvalid.value = true;
                void loadAllCategories({ force: false }).catch(() => { /* Retry on the next entry. */ });
            }
        });
    }

    return {
        // states
        allTransactionCategories,
        allTransactionCategoriesMap,
        transactionCategoryListStateInvalid,
        categoryRevision,
        // computed states
        allAvailablePrimaryCategoriesCount,
        allAvailableSecondaryCategoriesCount,
        hasVisibleExpenseCategories,
        hasVisibleIncomeCategories,
        hasVisibleTransferCategories,
        // functions
        updateTransactionCategoryListInvalidState,
        resetTransactionCategories,
        loadAllCategories,
        getCategory,
        previewImpact,
        saveCategory,
        addCategories,
        addPresetCategories,
        changeCategoryDisplayOrder,
        updateCategoryDisplayOrders,
        hideCategory,
        deleteCategory
    };
});
