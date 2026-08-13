package simpledb.test;

import java.sql.Types;
import java.util.Scanner;
import simpledb.plan.Plan;
import simpledb.plan.Planner;
import simpledb.query.Scan;
import simpledb.record.Schema;
import simpledb.server.SimpleDB;
import simpledb.tx.Transaction;

public class SimpleIJ {
   public static void main(String[] args) {
      Scanner sc = new Scanner(System.in);
      SimpleDB db = new SimpleDB("studentdb");
      Planner planner = db.planner();

      System.out.print("\nSQL> ");
      while (sc.hasNextLine()) {
         // process one line of input
         String cmd = sc.nextLine().trim();
         if (cmd.startsWith("exit"))
            break;
         else if (cmd.startsWith("select"))
            doQuery(db, planner, cmd);
         else
            doUpdate(db, planner, cmd);
         System.out.print("\nSQL> ");
      }
      sc.close();
   }

   private static void doQuery(SimpleDB db, Planner planner, String cmd) {
      Transaction tx = db.newTx();
      Scan s = null;

      try {
         Plan p = planner.createQueryPlan(cmd, tx);
         s = p.open();
         Schema sch = p.schema();
         int numcols = sch.fields().size();
         int totalwidth = 0;

         // print header
         for (int i=0; i<numcols; i++) {
            String fldname = sch.fields().get(i);
            int fldtype = sch.type(fldname);
            int fldlength = (fldtype == Types.INTEGER) ? 6 : sch.length(fldname);
            int width = Math.max(fldname.length(), fldlength) + 1;
            totalwidth += width;
            String fmt = "%" + width + "s";
            System.out.format(fmt, fldname);
         }
         System.out.println();
         for (int i=0; i<totalwidth; i++)
            System.out.print("-");
         System.out.println();

         // print records
         while (s.next()) {
            for (int i=0; i<numcols; i++) {
               String fldname = sch.fields().get(i);
               int fldtype = sch.type(fldname);
               int fldlength = (fldtype == Types.INTEGER) ? 6 : sch.length(fldname);
               int width = Math.max(fldname.length(), fldlength) + 1;
               String fmt = "%" + width;
               if (fldtype == Types.INTEGER) {
                  int ival = s.getInt(fldname);
                  System.out.format(fmt + "d", ival);
               }
               else {
                  String sval = s.getString(fldname);
                  System.out.format(fmt + "s", sval);
               }
            }
            System.out.println();
         }
         s.close();
         tx.commit();
      }
      catch (RuntimeException e) {
         if (s != null)
            s.close();
         tx.rollback();
         System.out.println("SQL Exception: " + e.getMessage());
      }
   }

   private static void doUpdate(SimpleDB db, Planner planner, String cmd) {
      Transaction tx = db.newTx();

      try {
         int howmany = planner.executeUpdate(cmd, tx);
         tx.commit();
         System.out.println(howmany + " records processed");
      }
      catch (RuntimeException e) {
         tx.rollback();
         System.out.println("SQL Exception: " + e.getMessage());
      }
   }
}
