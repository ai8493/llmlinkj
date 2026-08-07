package com.ai8493.llmproxy.model;

public record ModelInfo(String id, Long created, String ownedBy) {

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String id;
        private Long created;
        private String ownedBy;

        public Builder id(String id) { this.id = id; return this; }
        public Builder created(Long created) { this.created = created; return this; }
        public Builder ownedBy(String ownedBy) { this.ownedBy = ownedBy; return this; }

        public ModelInfo build() {
            return new ModelInfo(id, created, ownedBy);
        }
    }
}
