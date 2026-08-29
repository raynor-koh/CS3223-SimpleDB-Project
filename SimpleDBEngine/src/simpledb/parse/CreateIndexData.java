package simpledb.parse;

import java.util.Locale;

/**
 * The parser for the <i>create index</i> statement.
 * @author Edward Sciore
 */
public class CreateIndexData {
   private static final String HASH = "hash";
   private static final String BTREE = "btree";

   private String idxname, tblname, fldname, indexType;
   
   /**
    * Saves the table and field names of the specified index.
    */
   public CreateIndexData(String idxname, String tblname, String fldname, String indexType) {
       this.idxname = idxname;
       this.tblname = tblname;
       this.fldname = fldname;

       // Check if indexType supplied is an allowed string
       if (indexType == null) {
        throw new IllegalArgumentException("Index type cannot be null.");
       }

       String normalizedType = indexType.toLowerCase(Locale.ROOT);
       if (!normalizedType.equals(HASH) && !normalizedType.equals(BTREE)) {
        throw new IllegalArgumentException(
            "Unsupported index type: " + indexType
        );
       }

       this.indexType = normalizedType;
   }

   /**
    * backwards compatibility constructor for when the indexType is not specified.
    * Default is to use the hash index
    */
   public CreateIndexData(String idxname, String tblname, String fldname) {
       this(idxname, tblname, fldname, HASH);
   }
   
   /**
    * Returns the name of the index.
    * @return the name of the index
    */
   public String indexName() {
      return idxname;
   }
   
   /**
    * Returns the name of the indexed table.
    * @return the name of the indexed table
    */
   public String tableName() {
      return tblname;
   }
   
   /**
    * Returns the name of the indexed field.
    * @return the name of the indexed field
    */
   public String fieldName() {
      return fldname;
   }

   public String indexType() {
    return indexType;
   }
}
