import { describe, expect, it } from 'vitest';
import { CategoryType } from '@/core/category.ts';
import { TransactionCategory } from '@/models/transaction_category.ts';
import { getFirstVisibleCategoryId, hasAnyVisibleCategory } from '@/lib/category.ts';

describe('family internal categories', () => {
    it('keeps internal categories manually selectable without making them the default', () => {
        const parent = TransactionCategory.createNewCategory(CategoryType.Expense);
        const internal = TransactionCategory.of({ id: 'internal', name: '内部', parentId: '1', type: CategoryType.Expense, icon: '', iconType: 0, color: '', comment: '', displayOrder: 0, hidden: false, familyInternalTransfer: true });
        const ordinary = TransactionCategory.createNewCategory(CategoryType.Expense, '1');
        ordinary.id = 'ordinary';
        parent.subCategories = [internal];
        expect(internal.clone().familyInternalTransfer).toBe(true);
        const copy = TransactionCategory.createNewCategory();
        copy.fillFrom(internal);
        expect(copy.familyInternalTransfer).toBe(true);
        expect(hasAnyVisibleCategory([parent])).toBe(true);
        expect(getFirstVisibleCategoryId([parent])).toBe('');
        parent.subCategories.push(ordinary);
        expect(getFirstVisibleCategoryId([parent])).toBe('ordinary');
        parent.familyInternalTransfer = true;
        expect(getFirstVisibleCategoryId([parent])).toBe('');
        expect(hasAnyVisibleCategory([parent])).toBe(true);
    });
});
