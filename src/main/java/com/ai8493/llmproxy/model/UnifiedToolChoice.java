package com.ai8493.llmproxy.model;

public sealed interface UnifiedToolChoice {
    record None() implements UnifiedToolChoice {}
    record Auto() implements UnifiedToolChoice {}
    record Required(String functionName) implements UnifiedToolChoice {}
}
