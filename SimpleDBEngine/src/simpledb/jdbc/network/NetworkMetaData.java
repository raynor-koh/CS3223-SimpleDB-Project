package simpledb.jdbc.network;

import java.sql.*;
import simpledb.jdbc.ResultSetMetaDataAdapter;
import simpledb.query.Constant;

/**
 * An adapter class that wraps RemoteMetaData.
 * Its methods do nothing except transform RemoteExceptions
 * into SQLExceptions.
 * @author Edward Sciore
 */
public class NetworkMetaData extends ResultSetMetaDataAdapter {
   private RemoteMetaData rmd;
   
   public NetworkMetaData(RemoteMetaData md) {
      rmd = md;
   }
   
   public int getColumnCount() throws SQLException {
      try {
         return rmd.getColumnCount();
      }
      catch(Exception e) {
         throw new SQLException(e);
      }
   }
   
   public String getColumnName(int column) throws SQLException {
      try {
         return rmd.getColumnName(column);
      }
      catch (Exception e) {
         throw new SQLException(e);
      }
   }
   
   public int getColumnType(int column) throws SQLException {
      try {
         return rmd.getColumnType(column);
      }
      catch (Exception e) {
         throw new SQLException(e);
      }
   }

   public String getColumnTypeName(int column) throws SQLException {
      int type = getColumnType(column);
      if (type == java.sql.Types.DECIMAL) return "DECIMAL";
      if (type == java.sql.Types.TIME) return "TIME";
      if (type == java.sql.Types.INTEGER) return "INTEGER";
      return "VARCHAR";
   }

   public String getColumnClassName(int column) throws SQLException {
      int type = getColumnType(column);
      if (type == java.sql.Types.DECIMAL) return java.math.BigDecimal.class.getName();
      if (type == java.sql.Types.TIME) return java.time.LocalTime.class.getName();
      if (type == java.sql.Types.INTEGER) return Integer.class.getName();
      return String.class.getName();
   }

   public int getPrecision(int column) throws SQLException {
      if (getColumnType(column) == java.sql.Types.DECIMAL) return Constant.DECIMAL_PRECISION;
      return getColumnType(column) == java.sql.Types.TIME ? 15 : getColumnDisplaySize(column);
   }

   public int getScale(int column) throws SQLException {
      if (getColumnType(column) == java.sql.Types.DECIMAL) return Constant.DECIMAL_SCALE;
      return getColumnType(column) == java.sql.Types.TIME ? 6 : 0;
   }
   
   public int getColumnDisplaySize(int column) throws SQLException {
      try {
         return rmd.getColumnDisplaySize(column);
      }
      catch (Exception e) {
         throw new SQLException(e);
      }
   }
}

