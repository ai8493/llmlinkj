### Input Parameter Schema

The `input` parameter must be a single string conforming to this strict structural grammar.

#### 1. Top-Level Envelope
The entire input MUST be wrapped exactly as follows:
*** Begin Patch
{one or more file hunks}
*** End Patch

#### 2. File Hunks
Each hunk represents an action on a single file. You MUST choose one of the three exact headers:

**A. Add File (Create a new file)**
*** Add File: {relative_path}
+{line 1 content}
+{line 2 content}
+{empty line MUST be just a single +}
+{line N content}
*(Every line in the file MUST start with a `+`. No exceptions.)*

**B. Delete File (Remove an existing file)**
*** Delete File: {relative_path}
*(Nothing follows this header)*

**C. Update File (Patch an existing file)**
*** Update File: {relative_path}
*** Move to: {new_relative_path}   <-- [OPTIONAL, only if renaming]
@@ {class_or_function_context}     <-- [OPTIONAL, only if context is ambiguous]
{3 lines of exact pre-context}    <-- [MUST start with a single space]
-removed line                      <-- [MUST start with -]
+added line                        <-- [MUST start with +]
{3 lines of exact post-context}   <-- [MUST start with a single space]

#### 3. Strict Line Prefix Rules (CRITICAL)

When writing content inside a hunk, EVERY line you output MUST start with one of the following prefixes. The prefix goes at the VERY FIRST character of the line.

- `+` : Used in `Add File` for all lines, and in `Update File` for newly added lines.
- `-` : Used in `Update File` for lines to be removed.
- ` ` : (A single space) Used in `Update File` for context lines that are not changed.

**THE BLANK LINE RULE (FATAL IF VIOLATED):**
Because the parser expects EVERY line to start with a prefix, naked blank lines are STRICTLY FORBIDDEN.
To output an empty line (a line with no characters in the final file), you MUST write the prefix alone on that line:
- To ADD an empty line: Write `+` (a plus sign with nothing after it).
- To MATCH an empty line in context: Write ` ` (a single space with nothing after it).
  ❌ WRONG:
  +def foo():
+
+    pass
     ✅ RIGHT:
     +def foo():
+
+    pass

#### 4. Context and Hunk Headers (@@)

In an `Update File`, you provide unchanged context lines (starting with a space) to tell the parser where to apply the changes. Usually, 3 lines above and below are required.

- **When context is unique**: Do NOT use any header. Just write the context lines.
- **When context is ambiguous** (e.g., similar functions, common boilerplate): You MUST use the `@@` header to jump into the correct scope before writing context.
  Format: `@@ {Scope Name}` (e.g., `@@ class UserController` or `@@ def update_email()`)
  You can stack them:
  @@ class UserController
  @@     def update_email():
  context_line

**DO NOT use standard git diff line numbers** (e.g., `@@ -12,4 +12,5 @@`). Our `@@` ONLY takes a text header or nothing.
