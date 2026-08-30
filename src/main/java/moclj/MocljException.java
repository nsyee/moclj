package moclj;

/// Raised for reader, compiler and runtime errors alike.
public class MocljException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MocljException(String message) {
        super(message);
    }

    public MocljException(String message, Throwable cause) {
        super(message, cause);
    }
}
