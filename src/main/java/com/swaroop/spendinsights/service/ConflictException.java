package com.swaroop.spendinsights.service;

/** Raised when a create request collides with an existing unique resource, such as an email. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) { super(message); }
}
