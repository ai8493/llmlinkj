package com.ai8493.llmproxy.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class IndexedArgumentDeltaTest {

    @Test
    void shouldHoldIndexAndPartialJson() {
        IndexedArgumentDelta d = new IndexedArgumentDelta(0, "{\"city\":\"Beijing\"}");
        assertThat(d.index()).isEqualTo(0);
        assertThat(d.partialJson()).isEqualTo("{\"city\":\"Beijing\"}");
    }

    @Test
    void shouldAllowNullIndexForDefensive() {
        IndexedArgumentDelta d = new IndexedArgumentDelta(null, "partial");
        assertThat(d.index()).isNull();
        assertThat(d.partialJson()).isEqualTo("partial");
    }

    @Test
    void shouldBeValueEqual() {
        IndexedArgumentDelta d1 = new IndexedArgumentDelta(1, "a");
        IndexedArgumentDelta d2 = new IndexedArgumentDelta(1, "a");
        assertThat(d1).isEqualTo(d2);
        assertThat(d1.hashCode()).isEqualTo(d2.hashCode());
    }
}
