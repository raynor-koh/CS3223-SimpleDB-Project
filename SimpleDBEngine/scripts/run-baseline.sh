#!/usr/bin/env bash
# Run from any directory. Keep classes, logs, and databases for inspection.
set -euo pipefail

if (( $# != 0 )); then
    printf 'Usage: bash %s\n' "$0" >&2
    exit 2
fi

project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
for tool in java javac; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        printf 'Required tool not found: %s\n' "$tool" >&2
        exit 1
    fi
done

run_dir=$(mktemp -d /tmp/simpledb-baseline-XXXXXXXX)
classes_dir="$run_dir/classes"
mkdir -p "$classes_dir" "$run_dir/logs" "$run_dir/work"
printf 'Baseline artifacts: %s\n' "$run_dir"
java -version >"$run_dir/environment.log" 2>&1
javac -version >>"$run_dir/environment.log" 2>&1

mapfile -d '' -t sources < <(find "$project_dir/src" -type f -name '*.java' -print0)
if (( ${#sources[@]} == 0 )); then
    printf 'No Java sources found under %s/src\n' "$project_dir" >&2
    exit 1
fi
printf 'Compiling %d Java sources...\n' "${#sources[@]}"
if javac -d "$classes_dir" "${sources[@]}" >"$run_dir/logs/compile.log" 2>&1; then
    printf 'Compilation passed.\n'
else
    cat "$run_dir/logs/compile.log" >&2
    printf 'Compilation failed; artifacts: %s\n' "$run_dir" >&2
    exit 1
fi

tests=(
    simpledb.query.TermTest
    simpledb.parse.CreateIndexParserTest
    simpledb.parse.OrderByParserTest
    simpledb.parse.AggregateParserTest
    simpledb.parse.DistinctParserTest
    simpledb.parse.ExplainParserTest
    simpledb.parse.QueryValidationParserTest
    simpledb.plan.QueryValidationIntegrationTest
    simpledb.index.TypedIndexIntegrationTest
    simpledb.materialize.OrderByIntegrationTest
    simpledb.opt.JoinIntegrationTest
    simpledb.opt.JoinPolicyIntegrationTest
    simpledb.test.JoinFeatureIntegrationTest
    simpledb.test.QueryResourceIntegrationTest
    simpledb.test.IndexedUpdateAggregateIntegrationTest
    simpledb.test.AggregateSemanticsIntegrationTest
    simpledb.test.TimeTypeIntegrationTest
    simpledb.test.TimeTypeRegressionTest
    simpledb.multibuffer.HashJoinIntegrationTest
    simpledb.materialize.AggregateIntegrationTest
    simpledb.materialize.DistinctPlanTest
    simpledb.materialize.DistinctQueryIntegrationTest
    simpledb.plan.PlanExplainerTest
    simpledb.plan.ExplainIntegrationTest
)

passed=0
failed=0
for test_class in "${tests[@]}"; do
    work_dir="$run_dir/work/$test_class"
    log_file="$run_dir/logs/$test_class.log"
    mkdir -p "$work_dir/tmp"
    if (cd -- "$work_dir" && java -ea -Djava.io.tmpdir="$work_dir/tmp" \
            -cp "$classes_dir" "$test_class") >"$log_file" 2>&1; then
        passed=$((passed + 1))
        printf 'PASS %s\n' "$test_class"
        # Preserve each executable test's own check-count summary.
        while IFS= read -r line; do
            if [[ "$line" == All\ *passed.* ]]; then
                printf '  %s\n' "$line"
            fi
        done <"$log_file"
    else
        status=$?
        failed=$((failed + 1))
        printf 'FAIL %s (exit %d)\n' "$test_class" "$status" >&2
        cat "$log_file" >&2
    fi
done

printf '\nBaseline: %d passed, %d failed, %d total.\n' \
    "$passed" "$failed" "${#tests[@]}"
printf 'Classes, compiler output, test logs, and isolated databases: %s\n' "$run_dir"
if (( failed != 0 )); then
    exit 1
fi
