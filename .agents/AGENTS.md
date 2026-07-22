## Kotlin Code Generation & Debuggability Rules

When generating or editing Kotlin/Android code, you must adhere to the following standards:

1. **Structured Logging:**
   - All asynchronous components (Workers, Receivers, Repositories) must use Android `Log` statements (`Log.d`, `Log.w`, `Log.e`) with clear class-level tags (e.g., `TAG = "SmsLookupWorker"`).
   - Log critical operations, network response codes, and catch blocks (without exposing user PII).

2. **Explicit Typings:**
   - Always declare explicit return types and flow types for public functions, repositories, and ViewModels (do not rely on implicit type inference).

3. **Explicit Error Boundaries:**
   - Wrap network operations and database calls in `runCatching` blocks or `try-catch` structures.
   - Map raw exceptions to domain-specific sealed classes representing failure states so the UI can handle them cleanly.

4. **Testability:**
   - Write modular code (MVVM/UDF layers, repository pattern) that is decoupled and easy to test.
   - For every production class generated, provide a matching unit test structure.
