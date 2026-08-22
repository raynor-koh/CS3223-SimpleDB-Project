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
