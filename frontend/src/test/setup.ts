import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// Unmount between tests so a component left behind by one cannot be found by the next.
afterEach(() => {
  cleanup();
});
