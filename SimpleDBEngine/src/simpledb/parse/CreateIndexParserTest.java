package simpledb.parse;

/**
 * Deterministic tests for parsing typed CREATE INDEX statements.
 */
public class CreateIndexParserTest {
   private static int checksRun;

   public static void main(String[] args) {
      checkIndex(
            "create index i1 on student(majorid)",
            "i1", "student", "majorid", "hash");
      checkIndex(
            "create index i2 on student(majorid) using hash",
            "i2", "student", "majorid", "hash");
      checkIndex(
            "create index i3 on enroll(studentid) using btree",
            "i3", "enroll", "studentid", "btree");
      checkIndex(
            "CREATE INDEX MixedCase ON STUDENT(MajorId) USING BTREE",
            "mixedcase", "student", "majorid", "btree");

      expectBadSyntax(
            "create index bad_type on student(majorid) using bitmap");
      expectBadSyntax(
            "create index missing_type on student(majorid) using");
      expectInvalidType("bitmap");
      expectInvalidType(null);

      System.out.println(
            "All " + checksRun + " CREATE INDEX parser checks passed.");
   }

   private static void checkIndex(String sql, String expectedIndex,
         String expectedTable, String expectedField, String expectedType) {
      Object command = new Parser(sql).updateCmd();
      check(command instanceof CreateIndexData,
            "Expected CREATE INDEX data for: " + sql);

      CreateIndexData data = (CreateIndexData) command;
      check(expectedIndex.equals(data.indexName()),
            "Unexpected index name for: " + sql);
      check(expectedTable.equals(data.tableName()),
            "Unexpected table name for: " + sql);
      check(expectedField.equals(data.fieldName()),
            "Unexpected field name for: " + sql);
      check(expectedType.equals(data.indexType()),
            "Unexpected index type for: " + sql);
   }

   private static void expectBadSyntax(String sql) {
      try {
         new Parser(sql).updateCmd();
         throw new AssertionError("Expected invalid syntax for: " + sql);
      }
      catch (BadSyntaxException expected) {
         checksRun++;
      }
   }

   private static void expectInvalidType(String indexType) {
      try {
         new CreateIndexData("i", "t", "f", indexType);
         throw new AssertionError(
               "Expected invalid index type: " + indexType);
      }
      catch (IllegalArgumentException expected) {
         checksRun++;
      }
   }

   private static void check(boolean condition, String message) {
      checksRun++;
      if (!condition)
         throw new AssertionError(message);
   }
}
