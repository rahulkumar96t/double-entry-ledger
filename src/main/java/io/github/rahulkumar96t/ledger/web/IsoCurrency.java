package io.github.rahulkumar96t.ledger.web;

import io.github.rahulkumar96t.ledger.domain.Currencies;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** An upper-case ISO-4217 code with a defined minor unit ({@code EUR}, {@code JPY}; not {@code XAU}). */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = IsoCurrency.Validator.class)
public @interface IsoCurrency {

    String message() default "must be an ISO-4217 currency code with a minor unit, e.g. EUR";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<IsoCurrency, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || Currencies.isBookable(value);
        }
    }
}
