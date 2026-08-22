# Testing Guide

This serves to document the test cases adding for the features added for each lab session.

## Lab 1: Support Non-Equality Predicates

### Lexer consume new operators

Run the `src/simpledb/parse/LexerTest.java` file.

For valid comparison operators, the input will be echoed back into the terminal.
For invalid comparison operators, the terminal will print `Invalid comparison operator`.

| Test Cases | Output |
| --- | --- |
| age <= 18 | age <= 18 |
| age != 18 | age != 18 |
| 18 > age | 18 > age |
| age ! 8 | Invalid comparison operator |

### Update Term Grammar in Parser

Run `src/simpledb/parse/ParserTest.java`

If the parsing succeeded, `yes` will be printed.
If the parsing did not succeed, `no` will be printed instead.

| Test Cases | Output |
| --- | --- |
| select age from student where age >= 18 | yes |
| select age from student where age == 18 | no |
| select age from student where age <> 18 | no |
| select age from student where age = 18 | yes |
