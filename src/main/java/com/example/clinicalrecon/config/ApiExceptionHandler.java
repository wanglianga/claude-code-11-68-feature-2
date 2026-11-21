package com.example.clinicalrecon.config;

import com.example.clinicalrecon.domain.Dtos;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String msg) { super(msg); }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String msg) { super(msg); }
    }

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String msg) { super(msg); }
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Dtos.ErrorOut> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Dtos.ErrorOut("NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Dtos.ErrorOut> conflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Dtos.ErrorOut("CONFLICT", e.getMessage()));
    }

    @ExceptionHandler({BadRequestException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<Dtos.ErrorOut> badRequest(Exception e) {
        String msg = e instanceof MethodArgumentNotValidException m
                ? m.getBindingResult().getFieldErrors().stream()
                    .map(f -> f.getField() + " " + f.getDefaultMessage()).findFirst().orElse(e.getMessage())
                : e.getMessage();
        return ResponseEntity.badRequest().body(new Dtos.ErrorOut("BAD_REQUEST", msg));
    }
}
