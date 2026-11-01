# Tests

- TypeScript unit tests live under each package's `test/` directory and run with
  `pnpm check` or `pnpm --filter <package> test`.
- Android unit tests live in `apps/companion/app/src/test/` and run with
  `pnpm android:test`.
- Physical-device checks are documented in
  `tests/e2e/physical-phone-checklist.md` once the companion is implemented.
