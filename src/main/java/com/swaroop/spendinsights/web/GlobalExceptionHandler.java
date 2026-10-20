package com.swaroop.spendinsights.web;

import com.swaroop.spendinsights.service.BusinessRuleException;
import com.swaroop.spendinsights.service.NotFoundException;
import com.swaroop.spendinsights.web.dto.Dtos.ErrorResponse;
import com.swaroop.spendinsights.service.BadRequestException;
import com.swaroop.spendinsights.service.ConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(NotFoundException e) { return build(HttpStatus.NOT_FOUND, e.getMessage()); }

    @ExceptionHandler(BusinessRuleException.class)
    ResponseEntity<ErrorResponse> rule(BusinessRuleException e) { return build(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage()); }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ErrorResponse> conflict(ConflictException e) { return build(HttpStatus.CONFLICT, e.getMessage()); }

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<ErrorResponse> badRequest(BadRequestException e) { return build(HttpStatus.BAD_REQUEST, e.getMessage()); }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, "Malformed JSON or invalid field value");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException e) {
        return build(HttpStatus.BAD_REQUEST, e.getName() + " has an invalid value");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ErrorResponse> missing(MissingServletRequestParameterException e) {
        return build(HttpStatus.BAD_REQUEST, e.getParameterName() + " is required");
    }

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ErrorResponse> creds(BadCredentialsException e) { return build(HttpStatus.UNAUTHORIZED, e.getMessage()); }

    @ExceptionHandler(DateTimeParseException.class)
    ResponseEntity<ErrorResponse> date(DateTimeParseException e) { return build(HttpStatus.BAD_REQUEST, "Use month format YYYY-MM"); }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, msg);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus s, String msg) {
        return ResponseEntity.status(s).body(new ErrorResponse(s.value(), s.getReasonPhrase(), msg, Instant.now()));
    }
}
