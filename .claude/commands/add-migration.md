# Command: Adding a Database Migration

## Step 1: Find Next Version
```bash
ls backend/src/main/resources/db/migration/ | sort | tail -5
```
Current max: V15. Your migration: V16 (or higher if V16 already exists).

## Step 2: Create File
```bash
touch backend/src/main/resources/db/migration/V16__description.sql
```

## Step 3: Write SQL
```sql
-- Always use IF NOT EXISTS
ALTER TABLE table_name ADD COLUMN IF NOT EXISTS new_col TYPE DEFAULT value;
CREATE TABLE IF NOT EXISTS new_table (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ...
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_name ON table(column);
```

### Rules
- UUID with `gen_random_uuid()` — never SERIAL
- JSON as TEXT — R2DBC limitation
- Enums as VARCHAR(50)
- IF NOT EXISTS on everything

## Step 4: Add Kotlin Entity Field
```kotlin
// For new columns, add to the matching data class:
val newField: String? = null  // TEXT columns
val newField: Int = 0         // INT columns
```

## Step 5: Verify
```bash
cd backend && mvn compile -q
# Flyway runs automatically on boot — check for migration errors
```
