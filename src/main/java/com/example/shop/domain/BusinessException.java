package com.example.shop.domain;

/** A business rule refused the request; the message is safe to show to the user. */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
