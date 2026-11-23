package com.adperf.aggregator;

/** The input as a whole cannot be processed, e.g. it is empty or its header is invalid. */
public class InvalidInputException extends RuntimeException {

    public InvalidInputException(String message) {
        super(message);
    }
}
