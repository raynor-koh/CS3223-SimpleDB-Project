package simpledb.experiments;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;

import simpledb.index.planner.IndexUpdatePlanner;
import simpledb.index.planner.IndexJoinPlan;
import simpledb.materialize.MergeJoinPlan;
import simpledb.multibuffer.HashJoinPlan;
import simpledb.opt.HeuristicQueryPlanner;
import simpledb.opt.JoinMethod;
import simpledb.opt.JoinPolicy;
import simpledb.plan.NestedLoopJoinPlan;
import simpledb.plan.Plan;
import simpledb.plan.PlanExplainer;
import simpledb.plan.Planner;
import simpledb.plan.TablePlan;
import simpledb.query.Scan;
import simpledb.record.Layout;
import simpledb.record.TableScan;
import simpledb.server.SimpleDB;
import simpledb.tx.Transaction;

/** Experiment-only fixture/worker. Run through experiments/run.py. */
public final class JoinBenchmark {
    private static final String[] TABLES = {"benchfact", "benchmid", "benchdim", "benchtail"};
    private static final String[][] FIELDS = {
        {"fid", "fkey"}, {"mid", "mkey", "mnext"}, {"did", "dkey", "dnext"}, {"tid", "tkey"}
    };
    private static final String[] PADS = {"fpad", "mpad", "dpad", "tpad"};
    private static final String[] QUERIES = {
        "select fkey, mkey from benchfact, benchmid where fkey = mkey",
        "select fkey, mnext, dnext, tkey from benchfact, benchmid, benchdim, benchtail "
                + "where fkey = mkey and mnext = dkey and dnext = tkey"
    };
    private static final List<List<String>> OUTPUTS = List.of(
            List.of("fkey", "mkey"), List.of("fkey", "mnext", "dnext", "tkey"));
    private static final String CACHE = "fresh_engine_os_cache_uncontrolled";

    private JoinBenchmark() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 8) throw new IllegalArgumentException("Use experiments/run.py");
        String mode = args[0];
        Path snapshot = Path.of(args[1]);
        Path output = Path.of(args[2]);
        int size = Integer.parseInt(args[3]);
        long seed = Long.parseLong(args[4]);
        int payload = Integer.parseInt(args[5]);
        SimpleDB.BLOCK_SIZE = Integer.parseInt(args[6]);
        SimpleDB.BUFFER_SIZE = Integer.parseInt(args[7]);
        if (size < 16 || size > 100000 || payload < 0 || payload > 64
                || SimpleDB.BLOCK_SIZE < 400 || SimpleDB.BUFFER_SIZE < 8)
            throw new IllegalArgumentException("Invalid fixture/engine parameters");
        Dataset data = new Dataset(size, seed);
        if (mode.equals("prepare") && args.length == 8) {
            prepare(snapshot, output, data, payload);
        } else if (mode.equals("run") && args.length == 13) {
            int experiment = Integer.parseInt(args[8]);
            JoinMethod method = JoinMethod.valueOf(args[9]);
            int warmups = Integer.parseInt(args[10]);
            int runs = Integer.parseInt(args[11]);
            run(snapshot, output, Path.of(args[12]), data, experiment, method, warmups, runs);
        } else throw new IllegalArgumentException("Use experiments/run.py");
    }

    private static void prepare(Path snapshot, Path output, Dataset data, int payload) throws Exception {
        if (Files.exists(snapshot)) throw new IllegalArgumentException("Snapshot path must be new");
        Files.createDirectories(output);
        SimpleDB db = new SimpleDB(snapshot.toString());
        Transaction tx = db.newTx();
        StringBuilder ddl = new StringBuilder();
        StringBuilder manifest = new StringBuilder("table,rows,record_bytes,blocks,distinct_join_keys\n");
        try {
            for (int t = 0; t < TABLES.length; t++) {
                StringBuilder sql = new StringBuilder("create table " + TABLES[t] + "(");
                for (String field : FIELDS[t]) sql.append(field).append(" int, ");
                sql.append(PADS[t]).append(" varchar(").append(payload).append("))");
                db.planner().executeUpdate(sql.toString(), tx);
                ddl.append(sql).append('\n');
                Layout layout = db.mdMgr().getLayout(TABLES[t], tx);
                TableScan table = new TableScan(tx, TABLES[t], layout);
                try (BufferedWriter csv = Files.newBufferedWriter(output.resolve(TABLES[t] + ".csv"))) {
                    csv.write(String.join(",", FIELDS[t]) + "," + PADS[t] + "\n");
                    for (int[] row : data.tables.get(t)) {
                        table.insert();
                        for (int f = 0; f < row.length; f++) {
                            table.setInt(FIELDS[t][f], row[f]);
                            csv.write(row[f] + ",");
                        }
                        table.setString(PADS[t], "x".repeat(payload));
                        csv.write("x".repeat(payload) + "\n");
                    }
                } finally { table.close(); }
                long keys = data.tables.get(t).stream().map(row -> row[1]).distinct().count();
                manifest.append(TABLES[t]).append(',').append(data.tables.get(t).size()).append(',')
                        .append(layout.slotSize()).append(',').append(tx.size(TABLES[t] + ".tbl"))
                        .append(',').append(keys).append('\n');
                System.out.println("Loaded " + TABLES[t] + "; available buffers=" + tx.availableBuffs());
            }
            // Both ends of every equality edge are indexed, so any heuristic order is eligible.
            for (int t = 0; t < TABLES.length; t++) {
                int lastKey = t == 1 || t == 2 ? 2 : 1;
                for (int f = 1; f <= lastKey; f++) {
                    String sql = "create index ix_" + FIELDS[t][f] + " on " + TABLES[t]
                            + "(" + FIELDS[t][f] + ") using btree";
                    db.planner().executeUpdate(sql, tx);
                    System.out.println("Built ix_" + FIELDS[t][f] + "; available buffers=" + tx.availableBuffs());
                    ddl.append(sql).append('\n');
                }
            }
            require(tx.availableBuffs() == SimpleDB.BUFFER_SIZE,
                    "Fixture buffer count is " + tx.availableBuffs() + "; expected " + SimpleDB.BUFFER_SIZE);
            tx.commit();
        } catch (RuntimeException | Error | IOException e) {
            tx.rollback();
            throw e;
        }
        Files.writeString(output.resolve("schema.sql"), ddl);
        Files.writeString(output.resolve("tables.csv"), manifest);
        try (BufferedWriter writer = Files.newBufferedWriter(output.resolve("key-histograms.csv"))) {
            writer.write("table,field,key,frequency\n");
            for (int t = 0; t < TABLES.length; t++) {
                for (int field = 1; field < FIELDS[t].length; field++) {
                    Map<Integer, Integer> counts = new TreeMap<>();
                    for (int[] row : data.tables.get(t)) counts.merge(row[field], 1, Math::addExact);
                    for (Map.Entry<Integer, Integer> entry : counts.entrySet())
                        writer.write(TABLES[t] + "," + FIELDS[t][field] + ","
                                + entry.getKey() + "," + entry.getValue() + "\n");
                }
            }
        }
        Files.writeString(output.resolve("queries.sql"), String.join("\n", QUERIES) + "\n");
        try (BufferedWriter writer = Files.newBufferedWriter(output.resolve("expected.csv"))) {
            writer.write("experiment,output_rows,distinct_output_tuples\n");
            for (int experiment = 1; experiment <= 2; experiment++) {
                Map<List<Integer>, Integer> expected = data.expected(experiment);
                int count = expected.values().stream().mapToInt(Integer::intValue).sum();
                require(count == 2 * (data.size - (data.size + 19) / 20), "Reference output count is wrong");
                require(expected.values().stream().anyMatch(n -> n > 1), "Fixture must exercise duplicate tuples");
                writer.write(experiment + "," + count + "," + expected.size() + "\n");
            }
        }
        System.out.println("Prepared n" + data.size + " with seed " + data.seed);
    }

    private static void run(Path snapshot, Path output, Path work, Dataset data, int experiment,
            JoinMethod method, int warmups, int runs) throws Exception {
        require(experiment == 1 || experiment == 2, "Unknown experiment");
        require(warmups >= 0 && runs > 0 && warmups + runs <= 100, "Invalid repetition count");
        Files.createDirectories(output.resolve("plans"));
        Files.createDirectories(output.resolve("raw"));
        String stem = "n" + data.size + "-experiment-" + experiment + "-" + method.name().toLowerCase();
        Map<List<Integer>, Integer> expected = data.expected(experiment);
        List<String> fields = OUTPUTS.get(experiment - 1);
        int expectedRows = expected.values().stream().mapToInt(Integer::intValue).sum();
        String savedTree = null;
        try (BufferedWriter csv = Files.newBufferedWriter(output.resolve("raw").resolve(stem + ".csv"))) {
            csv.write("experiment,dataset_id,fact_rows,profile,plan_id,join_order,run,warmup,elapsed_ns,rows,"
                    + "result_verified,block_size,buffers,jdk,cache_policy,hash_fallback\n");
            for (int trial = 0; trial < warmups + runs; trial++) {
                Path trialDb = work.resolve("trial-" + trial);
                copyDatabase(snapshot, trialDb);
                // A fresh engine rebuilds statistics from the populated snapshot before planning.
                SimpleDB db = new SimpleDB(trialDb.toString());
                Transaction tx = db.newTx();
                boolean committed = false;
                try {
                    Planner planner = new Planner(new HeuristicQueryPlanner(db.mdMgr(), JoinPolicy.only(method)),
                            new IndexUpdatePlanner(db.mdMgr()));
                    Plan plan = planner.createQueryPlan(QUERIES[experiment - 1], tx);
                    validatePlan(plan, method, experiment == 1 ? 1 : 3);
                    String tree = PlanExplainer.explain(plan);
                    if (savedTree == null) {
                        Files.writeString(output.resolve("plans").resolve(stem + ".txt"), tree + "\n");
                        savedTree = tree;
                    } else require(savedTree.equals(tree), "Physical plan changed across repetitions");
                    List<String> order = new ArrayList<>();
                    tableOrder(plan, order);
                    int before = tx.availableBuffs();
                    // Allocation and full bag comparison stay outside the timed interval.
                    int[] tuples = new int[Math.multiplyExact(expectedRows, fields.size())];
                    int rows = 0;
                    Scan scan = null;
                    long start = System.nanoTime();
                    try {
                        scan = plan.open();
                        while (scan.next()) {
                            require(rows < expectedRows, "Query returned extra rows");
                            for (int f = 0; f < fields.size(); f++)
                                tuples[rows * fields.size() + f] = scan.getInt(fields.get(f));
                            rows++;
                        }
                    } finally { if (scan != null) scan.close(); }
                    long elapsed = System.nanoTime() - start;
                    require(tx.availableBuffs() == before, "Execution leaked buffers before commit");
                    require(rows == expectedRows, "Query returned too few rows");
                    Map<List<Integer>, Integer> actual = new HashMap<>();
                    for (int row = 0; row < rows; row++) {
                        List<Integer> tuple = new ArrayList<>();
                        for (int f = 0; f < fields.size(); f++) tuple.add(tuples[row * fields.size() + f]);
                        actual.merge(tuple, 1, Math::addExact);
                    }
                    require(expected.equals(actual), "Complete output bag differs from the independent Java reference");
                    tx.commit();
                    committed = true;
                    boolean warmup = trial < warmups;
                    csv.write(experiment + ",n" + data.size + "," + data.size + "," + method + ","
                            + digest(tree) + "," + String.join("->", order) + ","
                            + (warmup ? trial + 1 : trial - warmups + 1) + "," + warmup + ","
                            + elapsed + "," + rows + ",true," + SimpleDB.BLOCK_SIZE + ","
                            + SimpleDB.BUFFER_SIZE + "," + System.getProperty("java.version") + ","
                            + CACHE + "," + (method == JoinMethod.HASH ? "not_instrumented" : "not_applicable") + "\n");
                    csv.flush();
                    System.out.println(stem + " " + (warmup ? "warmup" : "measured")
                            + " " + (warmup ? trial + 1 : trial - warmups + 1)
                            + ": " + elapsed / 1_000_000.0 + " ms, " + rows + " rows verified");
                } catch (RuntimeException | Error | IOException e) {
                    if (!committed) tx.rollback();
                    throw e;
                }
            }
        }
    }

    private static void validatePlan(Plan plan, JoinMethod method, int count) {
        Class<?> expected = switch (method) {
            case INDEX -> IndexJoinPlan.class;
            case HASH -> HashJoinPlan.class;
            case MERGE -> MergeJoinPlan.class;
            case NESTED_LOOP -> NestedLoopJoinPlan.class;
        };
        require(countNodes(plan, expected) == count, "Wrong number of requested join operators");
        for (Class<?> kind : List.of(IndexJoinPlan.class, HashJoinPlan.class, MergeJoinPlan.class, NestedLoopJoinPlan.class))
            if (kind != expected) require(countNodes(plan, kind) == 0, "Unexpected join algorithm in plan");
        validateEstimates(plan);
    }

    private static void validateEstimates(Plan plan) {
        require(!plan.getClass().getSimpleName().contains("Product"), "Connected query became a Cartesian product");
        require(plan.blocksAccessed() >= 0 && plan.recordsOutput() >= 0,
                "Negative/overflowed plan estimate; reduce dataset size before reporting this plan");
        for (Plan child : plan.children()) validateEstimates(child);
    }

    private static int countNodes(Plan plan, Class<?> type) {
        int result = type.isInstance(plan) ? 1 : 0;
        for (Plan child : plan.children()) result += countNodes(child, type);
        return result;
    }

    private static void tableOrder(Plan plan, List<String> names) {
        if (plan instanceof TablePlan)
            names.add(plan.operationDescription().replace("TableScan(table=", "").replace(")", ""));
        for (Plan child : plan.children()) tableOrder(child, names);
    }

    private static String digest(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
    }

    private static void copyDatabase(Path source, Path target) throws IOException {
        try (Stream<Path> entries = Files.walk(source)) {
            for (Path path : entries.toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class Dataset {
        final int size;
        final long seed;
        final List<List<int[]>> tables = new ArrayList<>();

        Dataset(int size, long seed) {
            this.size = size;
            this.seed = seed;
            Random random = new Random(seed);
            int keys = Math.max(4, size / 8);
            int dimensions = Math.max(2, keys / 4);
            int tails = Math.max(2, dimensions / 4);
            for (int i = 0; i < 4; i++) tables.add(new ArrayList<>());
            for (int i = 0; i < size; i++)
                tables.get(0).add(new int[] {i, i % 20 == 0 ? keys : random.nextInt(keys)});
            for (int i = 0; i < keys * 2; i++)
                tables.get(1).add(new int[] {i, i / 2, random.nextInt(dimensions)});
            for (int i = 0; i < dimensions; i++)
                tables.get(2).add(new int[] {i, i, random.nextInt(tails)});
            for (int i = 0; i < tails; i++) tables.get(3).add(new int[] {i, i});
            for (List<int[]> table : tables) Collections.shuffle(table, random);
        }

        Map<List<Integer>, Integer> expected(int experiment) {
            Map<Integer, List<int[]>> middle = indexed(tables.get(1));
            Map<Integer, List<int[]>> dimension = indexed(tables.get(2));
            Map<Integer, List<int[]>> tail = indexed(tables.get(3));
            Map<List<Integer>, Integer> bag = new HashMap<>();
            for (int[] f : tables.get(0)) {
                for (int[] m : middle.getOrDefault(f[1], List.of())) {
                    if (experiment == 1) bag.merge(List.of(f[1], m[1]), 1, Math::addExact);
                    else for (int[] d : dimension.getOrDefault(m[2], List.of()))
                        for (int[] t : tail.getOrDefault(d[2], List.of()))
                            bag.merge(List.of(f[1], m[2], d[2], t[1]), 1, Math::addExact);
                }
            }
            return bag;
        }

        private static Map<Integer, List<int[]>> indexed(List<int[]> rows) {
            Map<Integer, List<int[]>> result = new HashMap<>();
            for (int[] row : rows) result.computeIfAbsent(row[1], key -> new ArrayList<>()).add(row);
            return result;
        }
    }
}
