package simpledb.query;

import simpledb.plan.Plan;
import simpledb.record.*;

/**
 * A term is a comparison between two expressions.
 * @author Edward Sciore
 *
 */
public class Term {
   private Expression lhs, rhs;
   private String operator;
   
   /**
    * Create a new term that compares two expressions
    * for equality.
     * This is kept for backward compatiability.
     * 
     * @param lhs the LHS expression
     * @param rhs the RHS expression
    */
   public Term(Expression lhs, Expression rhs) {
        this(lhs, "=", rhs);
    }

    /**
     * Create a new term that compares two expressions based on operator.
     * 
     * @param lhs      the LHR expression
     * @param operator the comparison operator
     * @param rhs      the RHS expression
     * 
     */
    public Term(Expression lhs, String operator, Expression rhs) {
      this.lhs = lhs;
      this.operator = operator;
      this.rhs = rhs;
   }
   
   /**
    * Return true if both of the term's expressions
    * evaluate to the same constant,
    * with respect to the specified scan.
    * @param s the scan
    * @return true if both expressions have the same value in the scan
    */
   public boolean isSatisfied(Scan s) {
      Constant lhsval = lhs.evaluate(s);
      Constant rhsval = rhs.evaluate(s);

        return compare(lhsval, rhsval);
    }

    /**
     * Compares two constants using the operator in this term.
     * 
     * @param lhsval value on the LHS
     * @param rhsval value on the RHS
     * @return true if the comparision is valid
     */
    private boolean compare(Constant lhsVal, Constant rhsVal) {
        // Preserve the SQL operand order: a negative result means lhs < rhs.
        // Comparing rhs to lhs instead would reverse the meaning of <, <=, >, and >=.
        int result = lhsVal.compareTo(rhsVal);

        switch (operator) {
            case "=":
                return result == 0;

            case "<":
                return result < 0;

            case "<=":
                return result <= 0;

            case ">":
                return result > 0;

            case ">=":
                return result >= 0;

            case "!=":
            case "<>":
                return result != 0;

            default:
                throw new IllegalArgumentException(
                        "Unsupported comparison operator: " + operator);
        }
   }
   
   /**
    * Calculate the extent to which selecting on the term reduces 
    * the number of records output by a query.
    * For example if the reduction factor is 2, then the
    * term cuts the size of the output in half.
    * @param p the query's plan
    * @return the integer reduction factor.
    */
   public int reductionFactor(Plan p) {
        if (!isEqualityComparison() && (lhs.isFieldName() || rhs.isFieldName())) {
            /*
             * SimpleDB does not maintain value ranges or distribution statistics,
             * so it cannot accurately estimate how many records satisfy an
             * inequality. Use a reduction factor of 2 to assume that approximately
             * half of the records match. This affects plan-cost estimates only,
             * not the actual predicate results.
             */
            return 2;
        }

      String lhsName, rhsName;
      if (lhs.isFieldName() && rhs.isFieldName()) {
         lhsName = lhs.asFieldName();
         rhsName = rhs.asFieldName();
         return Math.max(p.distinctValues(lhsName),
                         p.distinctValues(rhsName));
      }
      if (lhs.isFieldName()) {
         lhsName = lhs.asFieldName();
         return p.distinctValues(lhsName);
      }
      if (rhs.isFieldName()) {
         rhsName = rhs.asFieldName();
         return p.distinctValues(rhsName);
      }
      // otherwise, the term equates constants
        if (compare(lhs.asConstant(), rhs.asConstant()))
         return 1;
      else
         return Integer.MAX_VALUE;
   }

    /**
     * The optimizer uses equatesWithConstant() and equatesWithField() to
     * identify predicates suitable for equality-based optimizations, such
     * as index lookups. Inequality predicates must not be treated as
     * equalities because doing so could produce incorrect query results.
     * 
     * Returns true when this term uses the equality operator
     */
    private boolean isEqualityComparison() {
        return "=".equals(operator);
    }
   
   /**
    * Determine if this term is of the form "F=c"
    * where F is the specified field and c is some constant.
    * If so, the method returns that constant.
    * If not, the method returns null.
    * @param fldname the name of the field
    * @return either the constant or null
    */
   public Constant equatesWithConstant(String fldname) {
        if (!isEqualityComparison()) {
            return null;
        }

      if (lhs.isFieldName() &&
          lhs.asFieldName().equals(fldname) &&
          !rhs.isFieldName())
         return rhs.asConstant();
      else if (rhs.isFieldName() &&
               rhs.asFieldName().equals(fldname) &&
               !lhs.isFieldName())
         return lhs.asConstant();
      else
         return null;
   }
   
   /**
    * Determine if this term is of the form "F1=F2"
    * where F1 is the specified field and F2 is another field.
    * If so, the method returns the name of that field.
    * If not, the method returns null.
    * @param fldname the name of the field
    * @return either the name of the other field, or null
    */
   public String equatesWithField(String fldname) {
        if (!isEqualityComparison()) {
            return null;
        }

      if (lhs.isFieldName() &&
          lhs.asFieldName().equals(fldname) &&
          rhs.isFieldName())
         return rhs.asFieldName();
      else if (rhs.isFieldName() &&
               rhs.asFieldName().equals(fldname) &&
               lhs.isFieldName())
         return lhs.asFieldName();
      else
         return null;
   }
   
   /**
    * Return true if both of the term's expressions
    * apply to the specified schema.
    * @param sch the schema
    * @return true if both expressions apply to the schema
    */
   public boolean appliesTo(Schema sch) {
      return lhs.appliesTo(sch) && rhs.appliesTo(sch);
   }
   
   public String toString() {
        return lhs.toString() + operator + rhs.toString();
   }
}
