package net.citotech.cito.experience;

import java.time.Instant;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(
        assignableTypes = {
            ProductExperienceController.class,
            PublicExperienceController.class,
            MerchantOnboardingController.class
        })
public class ExperienceExceptionHandler {
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> unavailable(DataAccessException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(
                        Map.of(
                                "code", "DATA_TEMPORARILY_UNAVAILABLE",
                                "message",
                                        "Live data could not be retrieved. No fallback values were substituted.",
                                "timestamp", Instant.now()));
    }
}
