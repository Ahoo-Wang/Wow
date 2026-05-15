# Test Scenario Document Template

## Document Structure

```markdown
# <AggregateName> Test Cases

## <Command Handler Name>
| Scenario | Given | Expected | Status |
|----------|-------|----------|--------|
| <description> | <precondition> | <expected behavior> | Pending |

## <Saga Name>
| Scenario | Given | Expected | Status |
|----------|-------|----------|--------|
| <description> | <precondition> | <expected behavior> | Pending |
```

## Example

See `document/test-cases/ProductCostTestCases.md` in the project for a complete example.

## Coverage Requirements

For each command handler and saga, cover at minimum:

### Command Handlers

| Scenario Type | Description |
|---------------|-------------|
| Happy path | Valid command, succeeds with correct event |
| Idempotent/stale | Duplicate submission or old data handling |
| Edge case | Behavior under special state (e.g. reactivating disabled record) |
| Error path | Business rule validation failure, exception thrown |

### Saga

| Scenario Type | Description |
|---------------|-------------|
| Trigger condition met | Event + state satisfy conditions, correct commands generated |
| Trigger condition not met | Event + state don't satisfy conditions, no commands (if applicable) |
| Multi-element | Aggregate contains multiple sub-elements, batch command generation |

## Status Markers

- `Pending`: Scenario designed but not yet implemented
- `✓`: Test implemented and passing
- `✗`: Test implemented but failing (needs fix)
