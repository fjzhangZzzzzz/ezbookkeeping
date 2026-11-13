import { describe, expect, it, vi } from 'vitest';
import { reactive } from 'vue';
import { TransactionTag } from '@/models/transaction_tag.ts';
import { TransactionTagGroup } from '@/models/transaction_tag_group.ts';

const state = vi.hoisted(() => ({ store: {} as Record<string, unknown> }));
vi.mock('@/stores/transactionTag.ts', () => ({ useTransactionTagsStore: () => state.store }));
vi.mock('@/locales/helpers.ts', () => ({ useI18n: () => ({ tt: (value: string) => value }) }));

import { useTransactionTagSelectionBase } from '@/components/base/TransactionTagSelectionBase.ts';

describe('tag selection in hidden groups', () => {
    it('retains an existing selection but never offers other tags from a hidden group, even in search', () => {
        const group = reactive(TransactionTagGroup.of({ id: '1', name: 'group', displayOrder: 0, hidden: true }));
        const selected = TransactionTag.of({ id: '1', name: 'old', groupId: '1', displayOrder: 0, hidden: false });
        const other = TransactionTag.of({ id: '2', name: 'other', groupId: '1', displayOrder: 1, hidden: false });
        state.store = { allTransactionTagGroups: [group], allTransactionTagsByGroupMap: { '1': [selected, other] }, allTransactionTagsMap: { '1': selected, '2': other } };
        const props = reactive({ modelValue: ['1'] });
        const selector = useTransactionTagSelectionBase(props, false);
        expect(selector.allTagsWithGroupHeader.value).toContain(selected);
        expect(selector.allTagsWithGroupHeader.value).not.toContain(other);
        props.modelValue = [];
        selector.tagSearchContent.value = 'other';
        expect(selector.allTagsWithGroupHeader.value).not.toContain(other);
        expect(selector.filteredTagsWithGroupHeader.value).not.toContain(other);
        group.hidden = false;
        expect(selector.allTagsWithGroupHeader.value).toContain(other);
    });
});
