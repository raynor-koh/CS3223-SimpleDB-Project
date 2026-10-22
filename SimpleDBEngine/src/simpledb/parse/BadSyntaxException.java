package simpledb.parse;

/**
 * A runtime exception indicating that the submitted query
 * has incorrect syntax.
 * @author Edward Sciore
 */
@SuppressWarnings("serial")
public class BadSyntaxException extends IllegalArgumentException {
   public BadSyntaxException() {
      this("Invalid SQL syntax");
   }

   public BadSyntaxException(String message) {
      super(message);
   }

   public BadSyntaxException(String message, Throwable cause) {
      super(message, cause);
   }
}
