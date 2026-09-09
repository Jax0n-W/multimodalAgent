package com.multimodalAgent.agent.runtime.tool;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class ToolArgumentResolver {

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public ToolArgumentResolver(ObjectMapper objectMapper, Validator validator) {
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.objectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
    }

    public <I> I resolve(Map<String, Object> rawArguments, Class<I> inputType) {
        Objects.requireNonNull(rawArguments, "rawArguments must not be null");
        Objects.requireNonNull(inputType, "inputType must not be null");

        I input;
        try {
            input = objectMapper.convertValue(rawArguments, inputType); // 将原始参数转换为工具输入类型
        } catch (IllegalArgumentException exception) {
            throw new ToolValidationException(
                    "Arguments could not be converted to " + inputType.getSimpleName()
            );
        }

        Set<ConstraintViolation<I>> violations = validator.validate(input);  // 验证工具输入参数是否符合约束
        if (!violations.isEmpty()) {
            String details = violations.stream()
                    .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .collect(Collectors.joining(", "));
            throw new ToolValidationException("Invalid arguments: " + details);
        }
        return input;
    }
}
