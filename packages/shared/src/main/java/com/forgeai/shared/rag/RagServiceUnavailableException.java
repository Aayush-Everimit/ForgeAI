package com.forgeai.shared.rag;

public class RagServiceUnavailableException extends RuntimeException {

    public RagServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
