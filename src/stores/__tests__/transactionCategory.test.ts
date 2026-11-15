import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { TransactionCategory } from '@/models/transaction_category.ts';
import { Transaction } from '@/models/transaction.ts';
import { useTransactionsStore } from '@/stores/transaction.ts';
import { useTransactionCategoriesStore } from '@/stores/transactionCategory.ts';
import services from '@/lib/services.ts';

vi.hoisted(() => {
    const values = new Map<string, string>();
    const storage = { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => { values.set(key, value); }, removeItem: (key: string) => { values.delete(key); }, clear: () => values.clear() };
    Object.assign(globalThis, { window: { location: { pathname: '/', origin: 'http://localhost', href: 'http://localhost/' }, confirm: () => false, localStorage: storage, sessionStorage: storage }, localStorage: storage, sessionStorage: storage });
});

const revision = (value: string) => ({ data: { success: true, result: { revision: value } } }) as never;
const list = () => ({ data: { success: true, result: { 1: [], 2: [{ id: '1', name: '餐饮', parentId: '0', type: 2, icon: '0', iconType: 0, color: '#000000', comment: '', displayOrder: 1, hidden: false }], 3: [] } } }) as never;

describe('shared category revision', () => {
    beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); vi.restoreAllMocks(); });

    it('retries a list if its surrounding revisions differ', async () => {
        const getRevision = vi.spyOn(services, 'getTransactionCategoryRevision')
            .mockResolvedValueOnce(revision('1')).mockResolvedValueOnce(revision('2'))
            .mockResolvedValueOnce(revision('2')).mockResolvedValueOnce(revision('2'));
        const getList = vi.spyOn(services, 'getAllTransactionCategories').mockResolvedValue(list());
        const store = useTransactionCategoriesStore();
        await store.loadAllCategories({ force: false });
        expect(getRevision).toHaveBeenCalledTimes(4);
        expect(getList).toHaveBeenCalledTimes(2);
        expect(store.categoryRevision).toBe('2');
    });

    it('sends the observed revision and reloads after a successful write', async () => {
        vi.spyOn(services, 'getTransactionCategoryRevision')
            .mockResolvedValueOnce(revision('2')).mockResolvedValueOnce(revision('2'))
            .mockResolvedValueOnce(revision('3')).mockResolvedValueOnce(revision('3'));
        vi.spyOn(services, 'getAllTransactionCategories').mockResolvedValue(list());
        const hide = vi.spyOn(services, 'hideTransactionCategory').mockResolvedValue({ data: { success: true, result: true } } as never);
        const store = useTransactionCategoriesStore();
        await store.loadAllCategories({ force: false });
        await store.hideCategory({ category: store.allTransactionCategoriesMap['1']!, hidden: true, revision: '2' });
        expect(hide).toHaveBeenCalledWith({ id: '1', hidden: true, expectedRevision: '2' });
        expect(store.categoryRevision).toBe('3');
    });

    it('refreshes on conflict and surfaces an actionable error', async () => {
        vi.spyOn(services, 'getTransactionCategoryRevision')
            .mockResolvedValueOnce(revision('2')).mockResolvedValueOnce(revision('2'))
            .mockResolvedValueOnce(revision('4')).mockResolvedValueOnce(revision('4'));
        vi.spyOn(services, 'getAllTransactionCategories').mockResolvedValue(list());
        vi.spyOn(services, 'hideTransactionCategory').mockRejectedValue({ response: { data: { errorCode: 206013 } } });
        const store = useTransactionCategoriesStore();
        await store.loadAllCategories({ force: false });
        await expect(store.hideCategory({ category: store.allTransactionCategoriesMap['1']!, hidden: true, revision: '2' })).rejects.toMatchObject({ message: expect.stringContaining('分类已在其他设备修改') });
        expect(store.categoryRevision).toBe('4');
    });
    it('does not fall back to a newer store revision for an edit without its starting revision', async () => {
        const store = useTransactionCategoriesStore();
        const category = TransactionCategory.createNewCategory();
        const modify = vi.spyOn(services, 'modifyTransactionCategory');
        await expect(store.saveCategory({ category, isEdit: true, clientSessionId: '' })).rejects.toMatchObject({ message: expect.stringContaining('重新打开编辑页面') });
        expect(modify).not.toHaveBeenCalled();
    });

    it('tells the user when a successful write is followed by a failed refresh', async () => {
        vi.spyOn(services, 'getTransactionCategoryRevision')
            .mockResolvedValueOnce(revision('2')).mockResolvedValueOnce(revision('2'))
            .mockRejectedValueOnce(new Error('offline'));
        vi.spyOn(services, 'getAllTransactionCategories').mockResolvedValue(list());
        vi.spyOn(services, 'hideTransactionCategory').mockResolvedValue({ data: { success: true, result: true } } as never);
        const store = useTransactionCategoriesStore();
        await store.loadAllCategories({ force: false });
        await expect(store.hideCategory({ category: store.allTransactionCategoriesMap['1']!, hidden: true, revision: '2' })).rejects.toMatchObject({ message: expect.stringContaining('已保存') });
        expect(store.categoryRevision).toBeNull();
    });

    it('loads read-only categories from an older server without a revision endpoint', async () => {
        vi.spyOn(services, 'getTransactionCategoryRevision').mockRejectedValue({ response: { status: 404 } });
        vi.spyOn(services, 'getAllTransactionCategories').mockResolvedValue(list());
        const store = useTransactionCategoriesStore();
        await store.loadAllCategories({ force: false });
        expect(store.allTransactionCategoriesMap['1']?.name).toBe('餐饮');
        expect(store.categoryRevision).toBeNull();
        await expect(store.addCategories({ categories: [] })).rejects.toMatchObject({ message: expect.stringContaining('刷新分类') });
    });

    it('blocks repeating a create after an ambiguous network failure', async () => {
        const store = useTransactionCategoriesStore();
        const category = TransactionCategory.createNewCategory();
        const create = vi.spyOn(services, 'addTransactionCategory').mockRejectedValue(new Error('offline'));
        await expect(store.saveCategory({ category, isEdit: false, clientSessionId: 'fixture', editRevision: '2' })).rejects.toMatchObject({ message: expect.stringContaining('结果未知') });
        await expect(store.saveCategory({ category, isEdit: false, clientSessionId: 'fixture', editRevision: '2' })).rejects.toMatchObject({ message: expect.stringContaining('核对分类列表') });
        expect(create).toHaveBeenCalledTimes(1);
    });

    it('keeps an unknown-create marker across stores, scoped to the signed-in user', async () => {
        localStorage.setItem('ebk_user_info', JSON.stringify({ username: 'fixture-one' }));
        let store = useTransactionCategoriesStore();
        const category = TransactionCategory.createNewCategory();
        const create = vi.spyOn(services, 'addTransactionCategory').mockRejectedValue(new Error('offline'));
        await expect(store.saveCategory({ category, isEdit: false, clientSessionId: 'fixture', editRevision: '2' })).rejects.toBeTruthy();
        setActivePinia(createPinia());
        store = useTransactionCategoriesStore();
        await expect(store.saveCategory({ category, isEdit: false, clientSessionId: 'fixture', editRevision: '2' })).rejects.toMatchObject({ message: expect.stringContaining('解除创建阻止') });
        expect(create).toHaveBeenCalledTimes(1);
        localStorage.setItem('ebk_user_info', JSON.stringify({ username: 'fixture-two' }));
        await expect(store.saveCategory({ category, isEdit: false, clientSessionId: 'fixture', editRevision: '2' })).rejects.toMatchObject({ message: expect.stringContaining('结果未知') });
        expect(create).toHaveBeenCalledTimes(2);
    });

    it('refuses to send a create when browser storage cannot record an unknown outcome', async () => {
        const store = useTransactionCategoriesStore();
        const category = TransactionCategory.createNewCategory();
        const create = vi.spyOn(services, 'addTransactionCategory');
        vi.spyOn(localStorage, 'setItem').mockImplementation(() => { throw new Error('storage unavailable'); });
        await expect(store.saveCategory({ category, isEdit: false, clientSessionId: 'fixture', editRevision: '2' })).rejects.toBeTruthy();
        expect(create).not.toHaveBeenCalled();
    });

    it('records a pending create before sending and clears it after a definite revision conflict', async () => {
        vi.spyOn(services, 'getTransactionCategoryRevision').mockResolvedValue(revision('2'));
        vi.spyOn(services, 'getAllTransactionCategories').mockResolvedValue(list());
        const store = useTransactionCategoriesStore();
        const category = TransactionCategory.createNewCategory();
        const set = vi.spyOn(localStorage, 'setItem');
        const create = vi.spyOn(services, 'addTransactionCategory').mockImplementation(() => {
            expect(set.mock.calls.some(([key]) => key.startsWith('ebk_category_create_unknown_') && !key.endsWith('_probe'))).toBe(true);
            return Promise.reject({ response: { status: 409, data: { errorCode: 206013, errorMessage: 'conflict' } } });
        });
        await expect(store.saveCategory({ category, isEdit: false, clientSessionId: 'fixture', editRevision: '2' })).rejects.toBeTruthy();
        await expect(store.saveCategory({ category, isEdit: false, clientSessionId: 'fixture', editRevision: '2' })).rejects.toBeTruthy();
        expect(create).toHaveBeenCalledTimes(2);
    });

    it('records a transaction pending marker before sending and keeps it across store recreation', async () => {
        const transaction = Transaction.createNewTransaction(3, 1, 'Asia/Shanghai', 480);
        const set = vi.spyOn(localStorage, 'setItem');
        const create = vi.spyOn(services, 'addTransaction').mockImplementation(() => {
            expect(set.mock.calls.some(([key]) => key.startsWith('ebk_transaction_write_unknown_') && !key.endsWith('_probe'))).toBe(true);
            return Promise.reject(new Error('offline'));
        });
        let store = useTransactionsStore();
        const args = { transaction, defaultCurrency: 'CNY', isEdit: false, clientSessionId: 'fixture', expectedCategoryRevision: '2' };
        await expect(store.saveTransaction(args)).rejects.toMatchObject({ message: expect.stringContaining('结果未知') });
        setActivePinia(createPinia());
        store = useTransactionsStore();
        await expect(store.saveTransaction(args)).rejects.toMatchObject({ message: expect.stringContaining('核对账单') });
        expect(create).toHaveBeenCalledTimes(1);
    });

    it('keeps the create marker when a successful category response has no valid id', async () => {
        const store = useTransactionCategoriesStore();
        const category = TransactionCategory.createNewCategory();
        const create = vi.spyOn(services, 'addTransactionCategory').mockResolvedValue({ data: { success: true, result: {} } } as never);
        const args = { category, isEdit: false, clientSessionId: 'fixture', editRevision: '2' };
        await expect(store.saveCategory(args)).rejects.toMatchObject({ message: expect.stringContaining('结果未知') });
        await expect(store.saveCategory(args)).rejects.toMatchObject({ message: expect.stringContaining('解除创建阻止') });
        expect(create).toHaveBeenCalledTimes(1);
    });

    it('keeps the batch marker for an incomplete successful response', async () => {
        vi.spyOn(services, 'getTransactionCategoryRevision').mockResolvedValue(revision('2'));
        vi.spyOn(services, 'getAllTransactionCategories').mockResolvedValue(list());
        const create = vi.spyOn(services, 'addTransactionCategoryBatch').mockResolvedValue({ data: { success: true, result: {} } } as never);
        const store = useTransactionCategoriesStore();
        await store.loadAllCategories({ force: false });
        const request = { categories: [{ name: 'fixture', type: 2, icon: '1', iconType: 0, color: '000000', subCategories: [] }] } as never;
        await expect(store.addCategories(request)).rejects.toMatchObject({ message: expect.stringContaining('结果未知') });
        await expect(store.addCategories(request)).rejects.toMatchObject({ message: expect.stringContaining('解除创建阻止') });
        expect(create).toHaveBeenCalledTimes(1);
    });

    it('keeps the transaction marker when a successful response has no valid id', async () => {
        const transaction = Transaction.createNewTransaction(3, 1, 'Asia/Shanghai', 480);
        const create = vi.spyOn(services, 'addTransaction').mockResolvedValue({ data: { success: true, result: {} } } as never);
        const store = useTransactionsStore();
        const args = { transaction, defaultCurrency: 'CNY', isEdit: false, clientSessionId: 'fixture', expectedCategoryRevision: '2' };
        await expect(store.saveTransaction(args)).rejects.toMatchObject({ message: expect.stringContaining('结果未知') });
        await expect(store.saveTransaction(args)).rejects.toMatchObject({ message: expect.stringContaining('核对账单') });
        expect(create).toHaveBeenCalledTimes(1);
    });

    it('accepts a complete batch response with valid unique parent and child ids', async () => {
        vi.spyOn(services, 'getTransactionCategoryRevision').mockResolvedValue(revision('2'));
        vi.spyOn(services, 'getAllTransactionCategories').mockResolvedValue(list());
        vi.spyOn(services, 'addTransactionCategoryBatch').mockResolvedValue({ data: { success: true, result: { 2: [{ id: '10', subCategories: [{ id: '11' }] }] } } } as never);
        const store = useTransactionCategoriesStore();
        await store.loadAllCategories({ force: false });
        const request = { categories: [{ name: 'fixture', type: 2, icon: '1', iconType: 0, color: '000000', subCategories: [{ name: 'child' }] }] } as never;
        await expect(store.addCategories(request)).resolves.toBeTruthy();
    });

    it('keeps transaction pending state for a post-commit authentication 400', async () => {
        const transaction = Transaction.createNewTransaction(3, 1, 'Asia/Shanghai', 480);
        const create = vi.spyOn(services, 'addTransaction').mockRejectedValue({ response: { status: 400, data: { errorCode: 202011 } } });
        const store = useTransactionsStore();
        const args = { transaction, defaultCurrency: 'CNY', isEdit: false, clientSessionId: 'fixture', expectedCategoryRevision: '2' };
        await expect(store.saveTransaction(args)).rejects.toMatchObject({ message: expect.stringContaining('结果未知') });
        await expect(store.saveTransaction(args)).rejects.toMatchObject({ message: expect.stringContaining('核对账单') });
        expect(create).toHaveBeenCalledTimes(1);
    });

});
