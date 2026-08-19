

package de.dfki.sds.aticsqlite;

/**
 * Dataset capabilities configuration.
 */
public final class Capabilities {

    private final boolean rdfStarEnabled;
    private final boolean propertyTypeAware;

    private Capabilities(Builder builder) {
        this.rdfStarEnabled = builder.rdfStarEnabled;
        this.propertyTypeAware = builder.propertyTypeAware;
    }

    public boolean isRdfStarEnabled() {
        return rdfStarEnabled;
    }

    public boolean isPropertyTypeAware() {
        return propertyTypeAware;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final Capabilities DEFAULT = builder().build();

    public static final class Builder {

        private boolean rdfStarEnabled = true;
        private boolean propertyTypeAware = false;

        private Builder() {
        }

        public Builder rdfStarEnabled(boolean enabled) {
            this.rdfStarEnabled = enabled;
            return this;
        }

        public Builder propertyTypeAware(boolean enabled) {
            this.propertyTypeAware = enabled;
            return this;
        }

        public Capabilities build() {
            return new Capabilities(this);
        }
    }
}
