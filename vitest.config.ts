import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';
import path from 'node:path';

export default defineConfig({
    plugins: [vue()],
    resolve: {
        alias: {
            '@': path.resolve(__dirname, './src')
        }
    },
    test: {
        include: ['src/**/*.{test,spec}.{js,ts,jsx,tsx}'],
        environment: 'node',
        clearMocks: true,
        restoreMocks: true,
        mockReset: true,
        isolate: true,
        coverage: {
            provider: 'v8',
            reporter: ['text', 'html']
        }
    }
})
