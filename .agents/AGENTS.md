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

5. **Documentation & Licensing:**
   - Every `.kt` file must begin with the following BSD 3-Clause license header (substituting the filename where shown):

     ```
     /*
      * Copyright (c) 2025 Bill Roth <bill.roth@gmail.com>
      *
      * Redistribution and use in source and binary forms, with or without
      * modification, are permitted provided that the following conditions are met:
      *
      * 1. Redistributions of source code must retain the above copyright notice,
      *    this list of conditions and the following disclaimer.
      * 2. Redistributions in binary form must reproduce the above copyright notice,
      *    this list of conditions and the following disclaimer in the documentation
      *    and/or other materials provided with the distribution.
      * 3. Neither the name of the copyright holder nor the names of its contributors
      *    may be used to endorse or promote products derived from this software
      *    without specific prior written permission.
      *
      * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
      * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
      * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
      * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
      * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
      * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
      * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
      * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
      * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
      * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
      * POSSIBILITY OF SUCH DAMAGE.
      */
     ```

   - Every class, object, interface, and top-level function must have a KDoc comment (`/** ... */`) describing its purpose.
   - Every public method and property must have a KDoc comment. Private members should have KDoc where the logic is non-obvious.
   - Every constant (`const val`) must have an inline or KDoc comment explaining its purpose and, where applicable, its units or valid range.
   - KDoc must use `@param`, `@return`, and `@throws` tags for all non-trivial public functions.
   - Do not use end-of-line comments (`//`) as a substitute for KDoc on public API surfaces.
