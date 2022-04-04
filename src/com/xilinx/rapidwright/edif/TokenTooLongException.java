package com.xilinx.rapidwright.edif;

public class TokenTooLongException extends EDIFParseException{
    public TokenTooLongException(EDIFToken token, String message) {
        super(token, message);
    }

    public TokenTooLongException(String message) {
        super(message);
    }

    public TokenTooLongException(String message, Throwable cause) {
        super(message, cause);
    }
}
