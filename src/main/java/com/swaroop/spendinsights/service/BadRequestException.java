package com.swaroop.spendinsights.service;

/** Raised for invalid input that bean validation does not cover, such as pagination or timestamps. */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) { super(message); }
}
