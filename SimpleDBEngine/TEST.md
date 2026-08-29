# Testing Guide

This document records the test cases added for each lab feature.

## Lab 1: Support Non-Equality Predicates

### Lexer: Consume Comparison Operators

Run `src/simpledb/parse/LexerTest.java`.

For a valid comparison, the parsed operands and operator are printed in their
original order. For an invalid operator, the program prints
`Invalid comparison operator`.

| Input | Expected output |
| --- | --- |
| `age = 18` | `age = 18` |
| `age < 18` | `age < 18` |
| `age <= 18` | `age <= 18` |
| `age > 18` | `age > 18` |
| `age >= 18` | `age >= 18` |
| `age != 18` | `age != 18` |
| `age <> 18` | `age <> 18` |
| `age<=18` | `age <= 18` |
| `18 > age` | `18 > age` |
| `age ! 18` | `Invalid comparison operator` |

### Parser: Parse Non-Equality Terms

Run `src/simpledb/parse/ParserTest.java`.

The program prints `yes` when parsing succeeds and `no` when the statement has
invalid syntax. These tests validate integer constants, string constants,
field-to-field comparisons, and predicates connected by `AND`.

| Input | Expected output |
| --- | --- |
| `select age from student where age = 18` | `yes` |
| `select age from student where age < 18` | `yes` |
| `select age from student where age <= 18` | `yes` |
| `select age from student where age > 18` | `yes` |
| `select age from student where age >= 18` | `yes` |
| `select age from student where age != 18` | `yes` |
| `select age from student where age <> 18` | `yes` |
| `select name from student where name <> 'joe'` | `yes` |
| `select sid from student, enrol where sid < studentid` | `yes` |
| `select age, name from student where age >= 18 and name != 'joe'` | `yes` |
| `select age from student where age ! 18` | `no` |
| `select age from student where age == 18` | `no` |
| `select age from student where age >< 18` | `no` |

### Term: Deterministic Unit Tests

Run `src/simpledb/query/TermTest.java`.

This test uses in-memory `Scan` and `Plan` implementations. It does not create
or modify database files. It performs 58 checks in the following areas:

- all comparison operators for integers and strings;
- equality boundary cases for `<`, `<=`, `>`, and `>=`;
- left-to-right operand ordering;
- field-to-field, field-to-constant, and constant-to-field expressions;
- identical behavior for `!=` and `<>`;
- equality-only behavior in `equatesWithConstant()` and
  `equatesWithField()`;
- equality, inequality, and constant-to-constant reduction factors;
- backward compatibility of the two-argument `Term` constructor; and
- rejection of unsupported operators during evaluation.

Representative comparison cases include:

| Comparison | Expected result |
| --- | --- |
| `10 < 20` | `true` |
| `20 < 10` | `false` |
| `10 <= 10` | `true` |
| `10 > 10` | `false` |
| `10 >= 10` | `true` |
| `10 != 20` | `true` |
| `10 <> 10` | `false` |
| `'alice' < 'bob'` | `true` |
| `'alice' >= 'bob'` | `false` |
| `'alice' <> 'alice'` | `false` |

Representative optimizer-safety cases include:

| Term | Check | Expected result |
| --- | --- | --- |
| `A = 10` | `equatesWithConstant("A")` | `10` |
| `A > 10` | `equatesWithConstant("A")` | `null` |
| `A = B` | `equatesWithField("A")` | `B` |
| `A != B` | `equatesWithField("A")` | `null` |
| `A = 10` with 50 distinct `A` values | reduction factor | `50` |
| `A < 10` | reduction factor | `2` |
| `10 < 20` | reduction factor | `1` |
| `20 < 10` | reduction factor | `Integer.MAX_VALUE` |

The expected final output is:

```text
All 58 Term checks passed.
```

### SelectScan: Non-Equality Integration Tests

Run `src/simpledb/query/ScanTest3.java`.

This test creates a local `scantest3` database and uses a real
`TableScan -> SelectScan -> Predicate -> Term` pipeline. It clears its test
table before inserting these deterministic records:

| A | B | C | D |
| ---: | --- | ---: | --- |
| 5 | `alice` | 10 | `bob` |
| 10 | `bob` | 10 | `bob` |
| 15 | `carol` | 10 | `bob` |

The 13 integration checks are:

| Predicate | Expected record count |
| --- | ---: |
| `A < C` | 1 |
| `A <= C` | 2 |
| `A > C` | 1 |
| `A >= C` | 2 |
| `A != C` | 2 |
| `A <> C` | 2 |
| `B < D` | 1 |
| `B >= D` | 2 |
| `B != D` | 2 |
| `B <> D` | 2 |
| `A > 10` | 1 |
| `B <> 'bob'` | 2 |
| `A >= 10 and B <> 'bob'` | 1 |

The expected final output is:

```text
All 13 ScanTest3 checks passed.
```

The generated `scantest3/` database directory is a test artifact and should
not be committed.
