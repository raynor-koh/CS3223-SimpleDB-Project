package simpledb.jdbc.embedded;

import java.sql.SQLException;
import static java.sql.Types.INTEGER;
import simpledb.record.Schema;
import simpledb.jdbc.ResultSetMetaDataAdapter;

/**
 * The embedded implementation of ResultSetMetaData.
 * @author Edward Sciore
 */
public class EmbeddedMetaData extends ResultSetMetaDataAdapter {
   private Schema sch;
   
   /**
    * Creates a metadata object that wraps the specified schema.
    * The method also creates a list to hold the schema's
    * collection of field names,
    * so that the fields can be accessed by position.
    * @param sch the schema
    */
   public EmbeddedMetaData(Schema sch) {
      this.sch = sch;
   }
   
   /**
    * Returns the size of the field list.
    */
   public int getColumnCount() throws SQLException {
      return sch.fields().size();
   }
   
   /**
    * Returns the field name for the specified column number.
    * In JDBC, column numbers start with 1, so the field
    * is taken from position (column-1) in the list.
    */
   public String getColumnName(int column) throws SQLException {
      return sch.fields().get(column-1);
   }
   
   /**
    * Returns the type of the specified column.
    * The method first finds the name of the field in that column,
    * and then looks up its type in the schema.
    */
   public int getColumnType(int column) throws SQLException {
      String fldname = getColumnName(column);
      return sch.type(fldname);
   }

   public String getColumnTypeName(int column) throws SQLException {
      int type = getColumnType(column);
      if (type == java.sql.Types.TIME) return "TIME";
      if (type == INTEGER) return "INTEGER";
      return "VARCHAR";
   }

   public String getColumnClassName(int column) throws SQLException {
      int type = getColumnType(column);
      if (type == java.sql.Types.TIME) return java.time.LocalTime.class.getName();
      if (type == INTEGER) return Integer.class.getName();
      return String.class.getName();
   }

   public int getPrecision(int column) throws SQLException {
      return getColumnType(column) == java.sql.Types.TIME ? 15 : getColumnDisplaySize(column);
   }

   public int getScale(int column) throws SQLException {
      return getColumnType(column) == java.sql.Types.TIME ? 6 : 0;
   }
   
   /**
    * Returns the number of characters required to display the
    * specified column.
    * For a string-type field, the method simply looks up the 
    * field's length in the schema and returns that.
    * For an int-type field, the method needs to decide how
    * large integers can be.
    * Here, the method arbitrarily chooses 6 characters,
    * which means that integers over 999,999 will  
    * probably get displayed improperly.
    */
   public int getColumnDisplaySize(int column) throws SQLException {
      String fldname = getColumnName(column);
      int fldtype = sch.type(fldname);
      int fldlength = (fldtype == INTEGER) ? 6 : (fldtype == java.sql.Types.TIME ? 15 : sch.length(fldname));
      return Math.max(fldname.length(), fldlength) + 1;
   }
}
