package com.ilko.tournament.exception;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.NoHandlerFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {
	public record ErrorResponse(LocalDateTime timestamp, int status, String error, String message, String path) { }

	@ExceptionHandler(ResourceNotFoundException.class)
	ResponseEntity<ErrorResponse> notFound(ResourceNotFoundException exception, HttpServletRequest request) {
		return response(HttpStatus.NOT_FOUND, exception.getMessage(), request);
	}

	@ExceptionHandler(NoHandlerFoundException.class)
	ResponseEntity<ErrorResponse> endpointNotFound(NoHandlerFoundException exception, HttpServletRequest request) {
		return response(HttpStatus.NOT_FOUND, "Resource not found", request);
	}

	@ExceptionHandler(BusinessException.class)
	ResponseEntity<ErrorResponse> business(BusinessException exception, HttpServletRequest request) {
		return response(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
	}

	@ExceptionHandler(UnauthorizedOperationException.class)
	ResponseEntity<ErrorResponse> unauthorized(UnauthorizedOperationException exception, HttpServletRequest request) {
		return response(HttpStatus.FORBIDDEN, exception.getMessage(), request);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
		String message = exception.getBindingResult().getFieldErrors().stream().map(error -> error.getField() + ": " + error.getDefaultMessage()).collect(Collectors.joining(", "));
		return response(HttpStatus.BAD_REQUEST, message, request);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ErrorResponse> malformedRequest(HttpMessageNotReadableException exception, HttpServletRequest request) {
		return response(HttpStatus.BAD_REQUEST, "Request body is invalid", request);
	}

	@ExceptionHandler(DisabledException.class)
	ResponseEntity<ErrorResponse> disabledAccount(DisabledException exception, HttpServletRequest request) {
		return response(HttpStatus.FORBIDDEN, "This account has been blocked by an administrator", request);
	}

	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<ErrorResponse> authentication(AuthenticationException exception, HttpServletRequest request) {
		return response(HttpStatus.UNAUTHORIZED, "Invalid credentials", request);
	}

	@ExceptionHandler(ConflictException.class)
	ResponseEntity<ErrorResponse> applicationConflict(ConflictException exception, HttpServletRequest request) {
		return response(HttpStatus.CONFLICT, exception.getMessage(), request);
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ErrorResponse> accessDenied(AccessDeniedException exception, HttpServletRequest request) {
		return response(HttpStatus.FORBIDDEN, "You do not have permission to perform this action", request);
	}

	@ExceptionHandler({DataIntegrityViolationException.class, ObjectOptimisticLockingFailureException.class})
	ResponseEntity<ErrorResponse> conflict(Exception exception, HttpServletRequest request) {
		return response(HttpStatus.CONFLICT, "The resource was changed or already exists", request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> unexpected(Exception exception, HttpServletRequest request) {
		return response(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on the server", request);
	}

	private ResponseEntity<ErrorResponse> response(HttpStatus status, String message, HttpServletRequest request) {
		return ResponseEntity.status(status).body(new ErrorResponse(LocalDateTime.now(), status.value(), status.getReasonPhrase(), message, request.getRequestURI()));
	}
}
