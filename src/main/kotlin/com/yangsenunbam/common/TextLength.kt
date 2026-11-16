package com.yangsenunbam.common

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [TextLengthValidator::class])
annotation class TextLength(
    val min: Int = 0,
    val max: Int = Int.MAX_VALUE,
    val trim: Boolean = true,
    val message: String = "문자 수를 확인해 주세요.",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class TextLengthValidator : ConstraintValidator<TextLength, String?> {
    private var min = 0
    private var max = Int.MAX_VALUE
    private var trim = true

    override fun initialize(annotation: TextLength) {
        min = annotation.min
        max = annotation.max
        trim = annotation.trim
    }

    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean {
        if (value == null) return true
        val text = if (trim) value.trim() else value
        return text.codePointCount(0, text.length) in min..max
    }
}
