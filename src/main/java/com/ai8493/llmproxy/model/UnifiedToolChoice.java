package com.ai8493.llmproxy.model;

public sealed interface UnifiedToolChoice {

    record None() implements UnifiedToolChoice {
        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            public None build() { return new None(); }
        }
    }

    record Auto() implements UnifiedToolChoice {
        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            public Auto build() { return new Auto(); }
        }
    }

    record Required(String functionName) implements UnifiedToolChoice {
        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private String functionName;

            public Builder functionName(String functionName) { this.functionName = functionName; return this; }

            public Required build() { return new Required(functionName); }
        }
    }

    record Any() implements UnifiedToolChoice {
        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            public Any build() { return new Any(); }
        }
    }
}
