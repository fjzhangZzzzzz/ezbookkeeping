import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { TransactionTagGroup } from '@/models/transaction_tag_group.ts';
import { useTransactionTagsStore } from '@/stores/transactionTag.ts';
import services from '@/lib/services.ts';

vi.hoisted(() => {
    const storage = { getItem: () => null, setItem: () => {}, removeItem: () => {} };
    Object.assign(globalThis, { window: { location: { pathname: '/', origin: 'http://localhost', href: 'http://localhost/' }, localStorage: storage, sessionStorage: storage }, localStorage: storage, sessionStorage: storage });
});

describe('tag group visibility', () => {
    beforeEach(() => { setActivePinia(createPinia()); vi.restoreAllMocks(); });

    it('preserves visibility when decoding and cloning', () => {
        const group = TransactionTagGroup.of({ id: '1', name: 'group', displayOrder: 0, hidden: true });
        expect(group.clone().hidden).toBe(true);
        expect(TransactionTagGroup.createNewTagGroup().hidden).toBe(false);
    });

    it('changes local state only after the API succeeds', async () => {
        const group = TransactionTagGroup.of({ id: '1', name: 'group', displayOrder: 0, hidden: false });
        const store = useTransactionTagsStore();
        store.allTransactionTagGroups.push(group);
        store.allTransactionTagGroupsMap[group.id] = group;
        const request = vi.spyOn(services, 'hideTransactionTagGroup').mockResolvedValueOnce({ data: { success: false, result: false } } as never).mockResolvedValueOnce({ data: { success: true, result: true } } as never);
        await expect(store.hideTagGroup({ tagGroup: group, hidden: true })).rejects.toBeTruthy();
        expect(store.allTransactionTagGroupsMap['1']?.hidden).toBe(false);
        await store.hideTagGroup({ tagGroup: group, hidden: true });
        expect(request).toHaveBeenCalledWith({ id: '1', hidden: true });
        expect(store.allTransactionTagGroupsMap['1']?.hidden).toBe(true);
        expect(store.allTransactionTagGroups[0]?.hidden).toBe(true);
        request.mockRejectedValueOnce({ response: { data: { errorMessage: 'request failed' } } });
        await expect(store.hideTagGroup({ tagGroup: store.allTransactionTagGroups[0]!, hidden: false })).rejects.toEqual({ error: { errorMessage: 'request failed' } });
        expect(store.allTransactionTagGroupsMap['1']?.hidden).toBe(true);
    });
});
